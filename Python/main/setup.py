#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""xuegoongyun 打包脚本。

流程：
    EnvConfig     ：在初始化线程中捕获 MSVC 环境，并写入当前进程 os.environ
    cythonConfig  ：调用 Cython Python API 生成 C，再直接调用 cl.exe 编译为 pyd
    nuitkaConfig  ：调用 nuitka.__main__.main() Python API 完成 standalone 打包

设计约束：
    1. 不生成临时构建脚本，不依赖 setuptools。
    2. 不通过命令行调用 Nuitka / Cython，全部走 Python API；
       仅 setup.py 自身会用 -X frozen_modules=off 重新启动一次。
    3. 环境变量只写入当前进程一次，后续子进程自动继承。
    4. 三个配置类：EnvConfig、cythonConfig、nuitkaConfig。

用法：
    python setup.py              # 全量：Cython -> Nuitka
"""

from __future__ import annotations

import ast
import importlib.util
import os
import shutil
import subprocess
import sys
import sysconfig
import tempfile
import threading
import traceback
import types
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Sequence


def _应用覆盖(实例: object, 覆盖: dict[str, object]) -> None:
    """复制可变类属性并应用关键字覆盖，避免实例之间互相污染。"""
    for 名称, 值 in type(实例).__dict__.items():
        if isinstance(值, list):
            setattr(实例, 名称, list(值))
        elif isinstance(值, set):
            setattr(实例, 名称, set(值))
        elif isinstance(值, dict):
            setattr(实例, 名称, dict(值))

    for 键, 值 in 覆盖.items():
        if 键.startswith("_") or not hasattr(type(实例), 键):
            raise TypeError(f"未知配置项: {键}")

        原值 = getattr(type(实例), 键)
        if isinstance(原值, list):
            值 = list(值)
        elif isinstance(原值, set):
            值 = set(值)
        elif isinstance(原值, dict):
            值 = dict(值)
        elif isinstance(原值, tuple):
            值 = tuple(值)
        setattr(实例, 键, 值)


_重执行标记 = "XUEGOONGYUN_SETUP_REEXEC"


def _重执行构建解释器() -> int:
    """用 -X frozen_modules=off 重新启动构建脚本。

    Python 3.11+ 默认把 codecs/encodings 等模块标记为 frozen。Nuitka 生成
    standalone 的 frozen 模块表时会跳过这些模块，导致 exe 启动时报：

        Fatal Python error: init_fs_encoding ...
        ModuleNotFoundError: No module named 'codecs'

    Nuitka 官方入口在 re-exec 时会带 -X frozen_modules=off；本脚本在进程内
    调用 Nuitka API，必须自己保证构建解释器带同样的标志。该标志只能在解释器
    启动时指定，因此构建前重新启动 setup.py 一次。
    """
    根目录 = Path(__file__).resolve().parent
    环境 = dict(os.environ)
    环境[_重执行标记] = "1"
    环境["PYTHONHASHSEED"] = "0"

    命令 = [sys.executable]
    if sys.version_info >= (3, 11):
        命令.extend(["-X", "frozen_modules=off"])
    命令.append(str(Path(__file__).resolve()))

    print("重新启动构建解释器（关闭 frozen stdlib 模块）...", flush=True)
    print(f"执行: {subprocess.list2cmdline(命令)}", flush=True)

    try:
        结果 = subprocess.run(
            args=命令,
            cwd=str(根目录),
            env=环境,
        )
    except OSError as 异常:
        print(f"无法重新启动构建解释器: {异常}", flush=True)
        return 1

    return 结果.returncode


# ---------------------------------------------------------------------------
# 1. EnvConfig
# ---------------------------------------------------------------------------
class EnvConfig:
    """MSVC 环境配置。

    环境变量的值由本类提供：__捕获MSVC环境() 读取 vcvars64.bat 的 set 输出，
    与 附加环境变量 合并后由初始化线程写入当前进程 os.environ。
    """

    根目录: Path = Path(__file__).resolve().parent
    MSVC批处理: Path = Path(r"D:\VS\SDK\VC\Auxiliary\Build\vcvars64.bat")
    msvc_version: str | None = None  # None 表示从 VCToolsVersion 自动检测
    附加环境变量: dict[str, str] = {
        # 中文 Windows 下 SCons/clcache 可能用 mbcs 解码编译器输出，统一 UTF-8 模式规避
        "PYTHONUTF8": "1",
        "PYTHONIOENCODING": "utf-8",
        # "CL":"/c",
    }

    def __init__(self, **覆盖):
        _应用覆盖(实例=self, 覆盖=覆盖)
        self.根目录 = Path(self.根目录).resolve()
        self.MSVC批处理 = Path(self.MSVC批处理)

        self.环境变量: dict[str, str] = {}
        self._线程: threading.Thread | None = None
        self._错误: BaseException | None = None

        if not self.MSVC批处理.is_file():
            raise RuntimeError(f"MSVC 批处理不存在: {self.MSVC批处理}")

    def 开始初始化(self) -> threading.Thread:
        """启动环境初始化线程；重复调用返回同一个线程。"""
        if self._线程 is None:
            self._线程 = threading.Thread(
                target=self.__初始化线程,
                name="EnvConfig",
                daemon=True,
            )
            print("环境初始化线程已启动", flush=True)
            self._线程.start()
        return self._线程

    def 等待初始化(self) -> None:
        """等待初始化线程结束，并把线程内异常抛回主线程。"""
        if self._线程 is None:
            self.__初始化同步()
            return

        self._线程.join()
        self._线程 = None
        if self._错误 is not None:
            raise RuntimeError(f"环境初始化失败: {self._错误}") from self._错误

    def __初始化线程(self) -> None:
        try:
            self.__初始化同步()
        except BaseException as 异常:  # 线程内异常交回主线程处理
            self._错误 = 异常

    def __初始化同步(self) -> None:
        if self.环境变量:
            return

        print("正在捕获 MSVC 环境变量...", flush=True)
        环境 = self.__捕获MSVC环境()
        环境 = self.__合并环境(基础=环境, 新增=self.附加环境变量)
        self.__写入当前进程(环境=环境)
        self.环境变量 = 环境

        if self.msvc_version is None:
            self.msvc_version = self.__解析MSVC版本(环境=环境)

        PATH = next((值 for 键, 值 in 环境.items() if 键.upper() == "PATH"), "")
        if shutil.which(cmd="cl.exe", path=PATH) is None:
            raise RuntimeError("MSVC 环境中未找到 cl.exe，请检查 vcvars64.bat")

        print(f"MSVC 环境已写入当前进程，版本: {self.msvc_version}", flush=True)

    def __捕获MSVC环境(self) -> dict[str, str]:
        # 通过临时批处理调用 vcvars64，避免 cmd /c 嵌套引号在含空格路径下解析失败。
        批处理内容 = (
            "@echo off\r\n"
            f'call "{self.MSVC批处理}" >nul 2>&1\r\n'
            "set\r\n"
        )
        临时脚本: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="w",
                suffix=".bat",
                delete=False,
                encoding="mbcs",
                errors="replace",
                newline="",
            ) as 文件:
                文件.write(批处理内容)
                临时脚本 = Path(文件.name)

            结果 = subprocess.run(
                args=["cmd", "/c", str(临时脚本)],
                cwd=str(self.根目录),
                text=True,
                encoding="mbcs",
                errors="replace",
                capture_output=True,
            )
        finally:
            if 临时脚本 is not None:
                临时脚本.unlink(missing_ok=True)

        if 结果.returncode != 0 and not 结果.stdout:
            raise RuntimeError("vcvars64.bat 执行失败，未获取到 MSVC 环境")

        解析结果: dict[str, str] = {}
        for 行 in 结果.stdout.splitlines():
            if "=" not in 行:
                continue
            键, 值 = 行.split("=", 1)
            if not 键 or 键.startswith("="):
                continue
            解析结果[键] = 值

        if not 解析结果:
            raise RuntimeError("vcvars64.bat 未输出环境变量")

        return self.__合并环境(基础=dict(os.environ), 新增=解析结果)

    @staticmethod
    def __合并环境(基础: dict[str, str], 新增: dict[str, str]) -> dict[str, str]:
        """按 Windows 规则做大小写不敏感合并，新增值优先。"""
        合并 = dict(基础)
        索引 = {键.upper(): 键 for 键 in 合并}

        for 键, 值 in 新增.items():
            旧键 = 索引.get(键.upper())
            if 旧键 is not None and 旧键 != 键:
                合并.pop(旧键, None)
            合并[键] = 值
            索引[键.upper()] = 键

        return 合并

    @staticmethod
    def __写入当前进程(环境: dict[str, str]) -> None:
        for 键, 值 in 环境.items():
            for 旧键 in [名 for 名 in os.environ if 名.upper() == 键.upper()]:
                os.environ.pop(旧键, None)
            os.environ[键] = 值

    @staticmethod
    def __解析MSVC版本(环境: dict[str, str]) -> str:
        原始 = next(
            (值 for 键, 值 in 环境.items() if 键.upper() == "VCTOOLSVERSION"),
            "",
        ).strip()
        部分 = 原始.split(".")
        try:
            主版本 = int(部分[0])
            次版本 = int(部分[1][0])  # 14.51.36231 -> 14.5
        except (ValueError, IndexError):
            print(f"警告: 无法解析 VCToolsVersion={原始!r}，回退 --msvc=latest", flush=True)
            return "latest"

        版本 = f"{主版本}.{次版本}"
        print(f"检测到 MSVC 版本: {版本} (VCToolsVersion={原始})", flush=True)
        return 版本


# ---------------------------------------------------------------------------
# 2. cythonConfig
# ---------------------------------------------------------------------------
class cythonConfig:
    """Cython -> pyd 构建配置。

    不依赖 setuptools：
        1. Cython.Compiler.Main.compile() 生成 C 文件；
        2. 直接调用 cl.exe 编译 + 链接为 pyd。
    """

    根目录: Path = Path(__file__).resolve().parent
    入口文件: str = "main.py"

    # Cython 输出目录：c/、temp/、pyd/ 三个子目录都建在这里
    输出目录: Path = Path(r"F:\c#2\autogongxueyun\cythontemp")

    # 入口文件由 __发现Cython目标 自动排除，无需重复列出
    Cython排除: set[str] = {"setup.py"}
    # 手动填写：入口之外需要编译为 pyd 的附属 py 文件（相对根目录）
    附属py文件: list[str] = ["_dataclass.py", "OCR_Core.py"]

    Cython语言级别: int = 3
    Cython并行任务数: int = 22
    Cython包含目录: list[str] = []

    编译器: str = "cl.exe"
    # 对齐 setuptools 84.x 的 MSVC 默认参数：/GL + /MD + /DNDEBUG + /O2。
    # /GL 需要链接阶段 /LTCG；/cgthreads4 仅在 /GL 下生效。
    编译参数: list[str] = [
        "/nologo",
        "/O2",
        "/W3",
        "/GL",
        "/DNDEBUG",
        "/MD",
        "/cgthreads4",
        "/utf-8",
    ]
    链接参数: list[str] = ["/nologo", "/INCREMENTAL:NO", "/LTCG"]

    # None 表示按当前解释器自动推导
    Python包含目录: Path | None = None
    Python库目录: Path | None = None
    Python导入库: str | None = None

    验证pyd导入: bool = True
    导入校验超时: float = 180.0

    def __init__(self, **覆盖):
        _应用覆盖(实例=self, 覆盖=覆盖)

        if os.name != "nt":
            raise RuntimeError("当前打包脚本仅支持 Windows")

        self.根目录 = Path(self.根目录).resolve()
        self.输出目录 = Path(self.输出目录).resolve()
        self.Cython源码目录 = self.输出目录 / "c"
        self.Cython临时目录 = self.输出目录 / "temp"
        self.pyd目录 = self.输出目录 / "pyd"

        self.__初始化Python路径()
        self.cython_targets = self.__发现Cython目标()
        self.__打印摘要()

    def 构建(self) -> list[Path]:
        """生成全部 pyd，返回 pyd 路径列表。"""
        print("=" * 60, flush=True)
        print("阶段 1/2: Cython -> pyd (Cython API + cl.exe)", flush=True)
        print("=" * 60, flush=True)

        self.__清理()
        self.__准备目录()

        for 源文件 in self.cython_targets:
            self.__生成C源码(源文件=源文件)

        with ThreadPoolExecutor(max_workers=self.Cython并行任务数) as 线程池:
            pyd文件 = list(线程池.map(self.__编译pyd, self.cython_targets))

        self.__校验产物(pyd文件=pyd文件)
        if self.验证pyd导入:
            self.__校验导入()

        print(f"Cython 阶段完成，共生成 {len(pyd文件)} 个 pyd", flush=True)
        return pyd文件

    def __初始化Python路径(self) -> None:
        if self.Python包含目录 is None:
            self.Python包含目录 = Path(sysconfig.get_paths()["include"])
        else:
            self.Python包含目录 = Path(self.Python包含目录)

        if self.Python库目录 is None:
            self.Python库目录 = Path(sys.base_prefix) / "libs"
        else:
            self.Python库目录 = Path(self.Python库目录)

        if self.Python导入库 is None:
            self.Python导入库 = (
                f"python{sys.version_info.major}{sys.version_info.minor}.lib"
            )

        if not self.Python包含目录.is_dir():
            raise RuntimeError(f"Python 包含目录不存在: {self.Python包含目录}")

        if not (self.Python库目录 / self.Python导入库).is_file():
            raise RuntimeError(
                f"Python 导入库不存在: {self.Python库目录 / self.Python导入库}"
            )

        self.pyd后缀 = sysconfig.get_config_var(name="EXT_SUFFIX") or ".pyd"

    def __发现Cython目标(self) -> list[Path]:
        排除 = set(self.Cython排除) | {Path(self.入口文件).name}
        目标: list[Path] = []

        for 名称 in self.附属py文件:
            源文件 = self.根目录 / 名称
            if 源文件.name in 排除:
                continue
            if not 源文件.is_file():
                raise RuntimeError(f"附属 py 文件不存在: {源文件}")
            目标.append(源文件)

        模块名 = [源文件.stem for 源文件 in 目标]
        if len(模块名) != len(set(模块名)):
            raise RuntimeError("附属 py 文件存在同名模块，无法确定 pyd 名称")

        return 目标

    def __清理(self) -> None:
        if self.输出目录.exists():
            shutil.rmtree(path=self.输出目录)
            print(f"已删除: {self.输出目录}", flush=True)

    def __准备目录(self) -> None:
        for 目录 in (
            self.Cython源码目录,
            self.Cython临时目录,
            self.pyd目录,
        ):
            目录.mkdir(parents=True, exist_ok=True)

    def __生成C源码(self, 源文件: Path) -> Path:
        from Cython.Compiler.Main import compile as cython_compile
        from Cython.Compiler.Options import CompilationOptions

        C文件 = self.Cython源码目录 / f"{源文件.stem}.c"
        print(f"[Cython] {源文件.name} -> {C文件.name}", flush=True)

        结果 = cython_compile(
            source=str(源文件),
            options=CompilationOptions(
                compiler_directives={"language_level": self.Cython语言级别},
                cplus=False,
                cache=False,
                timestamps=False,
                quiet=True,
                output_file=str(C文件),
                include_path=[str(项) for 项 in self.Cython包含目录],
            ),
            full_module_name=源文件.stem,
        )

        if getattr(结果, "num_errors", 1) or not C文件.is_file():
            raise RuntimeError(f"Cython 编译失败: {源文件}")

        return C文件

    def __编译pyd(self, 源文件: Path) -> Path:
        C文件 = self.Cython源码目录 / f"{源文件.stem}.c"
        对象文件 = self.Cython临时目录 / f"{源文件.stem}.obj"
        导入库 = self.Cython临时目录 / f"{源文件.stem}.lib"
        链接产物 = self.Cython临时目录 / f"{源文件.stem}{self.pyd后缀}"
        pyd文件 = self.pyd目录 / f"{源文件.stem}{self.pyd后缀}"

        命令 = [
            self.编译器,
            *self.编译参数,
            "/LD",
            f"/I{self.Python包含目录}",
            str(C文件),
            f"/Fo{对象文件}",
            "/link",
            *self.链接参数,
            f"/OUT:{链接产物}",
            f"/IMPLIB:{导入库}",
            f"/LIBPATH:{self.Python库目录}",
            str(self.Python导入库),
        ]

        结果 = subprocess.run(args=命令, cwd=str(self.根目录))
        if 结果.returncode != 0:
            print(
                f"cl.exe 执行失败，退出码 {结果.returncode}: "
                + subprocess.list2cmdline(命令),
                flush=True,
            )
            raise RuntimeError(f"cl.exe 执行失败: {源文件.name}")

        if not 链接产物.is_file():
            raise RuntimeError(f"cl.exe 未生成 pyd: {链接产物}")

        # .exp/.lib/.obj 留在 temp，只把 pyd 放到交付目录
        链接产物.replace(target=pyd文件)
        print(f"[cl] {pyd文件.name}", flush=True)
        return pyd文件

    def __校验产物(self, pyd文件: Sequence[Path]) -> None:
        缺失 = [str(项) for 项 in pyd文件 if not 项.is_file()]
        if 缺失:
            raise RuntimeError("缺少 pyd:\n" + "\n".join(f"  - {项}" for 项 in 缺失))

    def __校验导入(self) -> None:
        if not self.cython_targets:
            return

        print("正在校验 pyd 可导入...", flush=True)
        模块名 = [源文件.stem for 源文件 in self.cython_targets]
        导入语句 = "; ".join(f"import {名称}" for 名称 in 模块名)
        代码 = f"import sys; sys.path.insert(0, sys.argv[1]); {导入语句}"

        结果 = subprocess.run(
            args=[sys.executable, "-c", 代码, str(self.pyd目录)],
            cwd=str(self.根目录),
            timeout=self.导入校验超时,
        )
        if 结果.returncode != 0:
            raise RuntimeError("pyd 导入校验失败")
        print("导入校验通过: " + ", ".join(模块名), flush=True)

    def __打印摘要(self) -> None:
        print(f"项目根目录: {self.根目录}", flush=True)
        print("Cython 目标: " + ", ".join(项.name for 项 in self.cython_targets), flush=True)
        print(f"pyd 输出目录: {self.pyd目录}", flush=True)
        print(f"Python 包含目录: {self.Python包含目录}", flush=True)
        print(f"Python 导入库: {self.Python库目录 / self.Python导入库}", flush=True)


# ---------------------------------------------------------------------------
# 3. nuitkaConfig
# ---------------------------------------------------------------------------
class nuitkaConfig:
    """Nuitka standalone 配置。

    通过 nuitka.__main__.main() 这个 Python 入口在进程内构建；
    Nuitka 4.x 没有接收参数的函数 API，参数按官方约定写入 sys.argv。
    """

    根目录: Path = Path(__file__).resolve().parent
    入口文件: str = "main.py"
    图标路径: Path | None = Path(r"F:\app常用图片\1.ico")

    # Nuitka 输出目录，最终产物位于 <输出目录>/<Nuitka输出文件夹名>
    输出目录: Path = Path(r"F:\c#2\autogongxueyun\EXEresult")
    输出文件名: str = "gongxueyunAuto.exe"
    Nuitka输出文件夹名: str | None = "CoreRustmatch"  # None 表示由输出文件名推导

    # ---- 资源：硬编码目标文件夹，构建时自动递归内部文件 ----
    资源目录定义: list[tuple[str, str]] = [
        ("Photo", "Photo/"),
        ("data", "data"),
        ("tesseract", "tesseract/"),
    ]
    资源文件定义: list[tuple[str, str]] = [
        ("Config.json", "Config.json"),
    ]

    jobs: int = 22
    lto: str = "yes"
    mode: str = "standalone"
    # Nuitka 会把该值原样传给 MSVC 的 /arch:，MSVC 只识别大写 AVX2；
    # 小写 avx2 会触发 D9002（未知选项）并被忽略。
    target_arch: str = "AVX2"
    reproducible: str = "no"
    console_mode: str = "force"
    static_libpython: str = "no"
    company_name: str = "XIAOXING.Tech Co., Ltd."
    product_name: str = "gongxueyunAuto"
    file_version: str = "0.0.1"
    file_description: str = "AutoGongXueYun"

    # 用于 AST 扫描 pyd 内部 import，自动推导 Nuitka 需要显式包含的第三方包
    依赖源文件: tuple[Path, ...] = ()
    包含包: list[str] = []
    包含包数据: list[str] = []
    # 所有 pyd 模块会自动加入；这里只放额外的、需要 Nuitka 停止跟进的模块
    不跟进模块: list[str] = []
    允许导入已排除模块: bool = True
    额外Nuitka参数: list[str] = []
    移除Nuitka构建缓存: bool = True
    # Windows standalone 首次构建需要 Dependency Walker，允许 Nuitka 自动下载到用户缓存
    允许Nuitka下载依赖: bool = True

    def __init__(self, **覆盖):
        _应用覆盖(实例=self, 覆盖=覆盖)

        self.根目录 = Path(self.根目录).resolve()
        self.图标路径 = Path(self.图标路径) if self.图标路径 else None
        self.输出目录 = Path(self.输出目录).resolve()
        self.资源目录 = [
            (self.根目录 / 源, 目标) for 源, 目标 in self.资源目录定义
        ]
        self.资源文件 = [
            (self.根目录 / 源, 目标) for 源, 目标 in self.资源文件定义
        ]

        self.__校验基础()
        自动依赖包 = self.__自动收集依赖包(源文件列表=self.依赖源文件)
        self.包含包 = sorted(set(self.包含包) | set(自动依赖包))
        self.__打印摘要()

    def 构建(
        self,
        *,
        pyd文件: Sequence[Path],
        模块名: Sequence[str],
        msvc_version: str | None = None,
    ) -> Path:
        """调用 Nuitka Python API 完成 standalone 构建。

        模块名 必须来自源文件 stem（如 `_dataclass`），不能使用 pyd 文件名 stem
        （如 `_dataclass.cp312-win_amd64`），否则 --nofollow-import-to 匹配不到
        真正的模块，Nuitka 会把同名 .py 源码重新编进 exe。
        """
        print("=" * 60, flush=True)
        print("阶段 2/2: Nuitka standalone (Python API)", flush=True)
        print("=" * 60, flush=True)

        if len(模块名) != len(pyd文件):
            raise RuntimeError(
                f"模块名数量与 pyd 数量不一致: {len(模块名)} != {len(pyd文件)}"
            )

        if self.依赖源文件:
            允许模块名 = {源文件.stem for 源文件 in self.依赖源文件}
            非法模块名 = [名称 for 名称 in 模块名 if 名称 not in 允许模块名]
            if 非法模块名:
                raise RuntimeError(
                    "模块名必须来自源文件 stem，当前不在 依赖源文件 中: "
                    + ", ".join(非法模块名)
                )

        for 名称, 项 in zip(模块名, pyd文件, strict=True):
            if not 项.name.startswith(f"{名称}."):
                raise RuntimeError(f"pyd 与模块名不匹配: {名称} -> {项.name}")
            if 名称 not in self.不跟进模块:
                self.不跟进模块.append(名称)

        self.__清理输出()
        参数 = self.__构建参数(pyd文件=pyd文件, msvc_version=msvc_version)
        self.__调用Nuitka(参数=参数)
        return self.__检查产物(pyd文件=pyd文件)

    def __校验基础(self) -> None:
        if self.图标路径 and not self.图标路径.is_file():
            print(f"警告: 图标不存在，已跳过: {self.图标路径}", flush=True)
            self.图标路径 = None

        入口文件 = self.根目录 / self.入口文件
        if not 入口文件.is_file():
            raise RuntimeError(f"入口文件不存在: {入口文件}")

        for 源目录, _目标目录 in self.资源目录:
            if not 源目录.is_dir():
                raise RuntimeError(f"资源目录不存在: {源目录}")

        for 源文件, _目标文件 in self.资源文件:
            if not 源文件.is_file():
                raise RuntimeError(f"资源文件不存在: {源文件}")

    def __自动收集依赖包(self, 源文件列表: Sequence[Path]) -> list[str]:
        """扫描附属 py 的顶层 import，推导 Nuitka 需要显式包含的第三方包。"""
        本地模块 = {源文件.stem for 源文件 in 源文件列表}
        本地模块.add(Path(self.入口文件).stem)
        名称集合: set[str] = set()

        for 源文件 in 源文件列表:
            语法树 = ast.parse(
                source=源文件.read_text(encoding="utf-8"),
                filename=str(源文件),
            )
            for 节点 in ast.walk(语法树):
                if isinstance(节点, ast.Import):
                    名称集合.update(
                        别名.name.split(".")[0] for 别名 in 节点.names
                    )
                elif (
                    isinstance(节点, ast.ImportFrom)
                    and 节点.level == 0
                    and 节点.module
                ):
                    名称集合.add(节点.module.split(".")[0])

        包列表: list[str] = []
        for 名称 in sorted(名称集合):
            if 名称 in 本地模块 or 名称 in sys.stdlib_module_names:
                continue
            try:
                if importlib.util.find_spec(name=名称) is None:
                    continue
            except (ImportError, ValueError, AttributeError):
                continue
            包列表.append(名称)

        print("自动收集依赖包: " + (", ".join(包列表) if 包列表 else "(无)"), flush=True)
        return 包列表

    def __输出文件夹名(self) -> str:
        名称 = self.Nuitka输出文件夹名 or Path(self.输出文件名).stem
        # Nuitka standalone 会自动追加 .dist；显式补齐，保证产物检查路径与 Nuitka 一致
        if self.mode == "standalone" and not 名称.lower().endswith(".dist"):
            名称 += ".dist"
        return 名称

    def __产物目录(self) -> Path:
        return self.输出目录 / self.__输出文件夹名()

    def __清理输出(self) -> None:
        产物目录 = self.__产物目录()
        if 产物目录.exists():
            shutil.rmtree(path=产物目录)
            print(f"已删除: {产物目录}", flush=True)

    def __构建参数(
        self,
        *,
        pyd文件: Sequence[Path],
        msvc_version: str | None,
    ) -> list[str]:
        目标架构 = self.target_arch
        if 目标架构.lower() in {"sse2", "avx", "avx2", "avx512"}:
            目标架构 = 目标架构.upper()

        参数 = [
            f"--mode={self.mode}",
            f"--msvc={msvc_version or 'latest'}",
            f"--jobs={self.jobs}",
            f"--lto={self.lto}",
            f"--static-libpython={self.static_libpython}",
            f"--target-arch={目标架构}",
            f"--reproducible={self.reproducible}",
            f"--windows-console-mode={self.console_mode}",
            f"--company-name={self.company_name}",
            f"--product-name={self.product_name}",
            f"--file-version={self.file_version}",
            f"--file-description={self.file_description}",
            f"--output-dir={self.输出目录}",
            f"--output-folder-name={self.__输出文件夹名()}",
            f"--output-filename={self.输出文件名}",
        ]

        if self.图标路径:
            参数.append(f"--windows-icon-from-ico={self.图标路径}")
        if self.允许Nuitka下载依赖:
            参数.append("--assume-yes-for-downloads")
        if self.移除Nuitka构建缓存:
            参数.append("--remove-output")

        for 模块 in self.不跟进模块:
            参数.append(f"--nofollow-import-to={模块}")
        if self.允许导入已排除模块 and self.不跟进模块:
            参数.append("--no-deployment-flag=excluded-module-usage")
        for 包 in self.包含包:
            参数.append(f"--include-package={包}")
        for 包 in self.包含包数据:
            参数.append(f"--include-package-data={包}")

        # 资源目录：整目录递归复制并保留目录结构。
        # 不能用 --include-data-files=...=**/*：它会匹配到子目录并触发
        # "cannot include directory ... as a data file" 的 FATAL；
        # 也不能用 --include-data-dir：它默认忽略 .dll/.exe，会漏掉 tesseract 依赖。
        for 源目录, 目标目录 in self.资源目录:
            目标前缀 = 目标目录.strip("/\\")
            参数.append(f"--include-raw-dir={源目录}={目标前缀}")
        for 源文件, 目标文件 in self.资源文件:
            参数.append(f"--include-data-files={源文件}={目标文件}")
        for 项 in pyd文件:
            参数.append(f"--include-data-files={项}={项.name}")

        参数.extend(self.额外Nuitka参数)
        参数.append(str(self.根目录 / self.入口文件))
        return 参数

    def __调用Nuitka(self, 参数: Sequence[str]) -> None:
        """在当前进程内调用 Nuitka。

        Nuitka 4.x 的入口 nuitka.__main__.main() 不接受函数参数，只能读取
        sys.argv；并且默认会 re-exec 整个进程，导致 main() 不返回。

        官方入口在检测到 sys.modules 中存在 debugpy 时会跳过 re-exec
        （用于调试器场景）。这里临时注入一个占位模块，让构建在当前进程完成，
        便于构建后继续做产物校验；调用结束后恢复 sys.argv 并移除占位模块。
        """
        from nuitka.__main__ import main as nuitka_main

        print("Nuitka API 调用: " + " ".join(参数), flush=True)

        注入debugpy = "debugpy" not in sys.modules
        if 注入debugpy:
            sys.modules["debugpy"] = types.ModuleType(name="debugpy")

        原参数 = sys.argv
        sys.argv = ["nuitka", *参数]
        try:
            nuitka_main()
        except SystemExit as 异常:
            代码 = 异常.code
            if 代码 not in (None, 0):
                raise RuntimeError(f"Nuitka 构建失败，退出码 {代码}") from 异常
        finally:
            sys.argv = 原参数
            if 注入debugpy:
                sys.modules.pop("debugpy", None)

        print("Nuitka API 返回", flush=True)

    def __检查产物(self, *, pyd文件: Sequence[Path]) -> Path:
        产物目录 = self.__产物目录()
        可执行文件 = 产物目录 / self.输出文件名
        if not 可执行文件.is_file():
            现有 = (
                sorted(str(项) for 项 in self.输出目录.iterdir())
                if self.输出目录.is_dir()
                else []
            )
            raise RuntimeError(
                f"未找到可执行文件: {可执行文件}\n"
                f"输出目录内容: {现有}"
            )

        缺失: list[str] = []

        for 项 in pyd文件:
            if not (产物目录 / 项.name).is_file():
                缺失.append(项.name)

        for _源文件, 目标文件 in self.资源文件:
            if not (产物目录 / 目标文件).is_file():
                缺失.append(目标文件)

        for 源目录, 目标目录 in self.资源目录:
            目标根 = 产物目录 / 目标目录.strip("/\\")
            if not 目标根.is_dir():
                缺失.append(目标目录)
                continue

            源文件集合 = {
                项.relative_to(源目录) for 项 in 源目录.rglob("*") if 项.is_file()
            }
            目标文件集合 = {
                项.relative_to(目标根) for 项 in 目标根.rglob("*") if 项.is_file()
            }
            for 相对路径 in sorted(源文件集合 - 目标文件集合):
                缺失.append(str(Path(目标目录.strip("/\\")) / 相对路径))

        if 缺失:
            raise RuntimeError(
                "产物缺少以下文件:\n" + "\n".join(f"  - {项}" for 项 in 缺失)
            )

        print("Nuitka 打包完成", flush=True)
        print(f"产物目录: {产物目录}", flush=True)
        print(f"可执行文件: {可执行文件}", flush=True)
        return 可执行文件

    def __打印摘要(self) -> None:
        print(f"项目根目录: {self.根目录}", flush=True)
        print(f"入口文件: {self.入口文件}", flush=True)
        print(
            "Nuitka 包含包: " + (", ".join(self.包含包) if self.包含包 else "(无)"),
            flush=True,
        )
        print(
            "Nuitka 包含包数据: "
            + (", ".join(self.包含包数据) if self.包含包数据 else "(无)"),
            flush=True,
        )
        print(f"Nuitka 输出目录: {self.输出目录}", flush=True)
        print(f"Nuitka 输出文件夹: {self.__输出文件夹名()}", flush=True)


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------
def main() -> int:
    try:
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        if hasattr(sys.stderr, "reconfigure"):
            sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    # Nuitka standalone 必须在 frozen stdlib 模块关闭的解释器中构建。
    if os.environ.get(_重执行标记) != "1":
        return _重执行构建解释器()

    try:
        环境 = EnvConfig()
        环境.开始初始化()

        cython_配置 = cythonConfig()
        nuitka_配置 = nuitkaConfig(依赖源文件=cython_配置.cython_targets)

        环境.等待初始化()
        pyd文件 = cython_配置.构建()

        nuitka_配置.构建(
            pyd文件=pyd文件,
            模块名=[源文件.stem for 源文件 in cython_配置.cython_targets],
            msvc_version=环境.msvc_version,
        )

    except Exception as 异常:
        print(f"构建失败: {异常}", flush=True)
        traceback.print_exc()
        return 1

    print("全部完成", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
