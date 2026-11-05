#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""xuegoongyun 打包脚本。

流程：
    1. Config      ：MSVC 环境/版本与附属 py 依赖包并行采集、参数硬编码
    2. CythonBuild ：附属 py 文件编译为 pyd
    3. NuitkaBuild ：pyd、Photo、Config.json 作为资源，使用 Nuitka standalone 打包

用法：
    python setup.py              # 全量：Cython -> Nuitka
    python setup.py cython       # 仅生成 pyd
    python setup.py nuitka       # 复用已有 pyd，仅跑 Nuitka
    python setup.py clean        # 清理 build / dist / Nuitka 临时目录
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
import time
import traceback
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Sequence


# ---------------------------------------------------------------------------
# 模块级私有工具函数
#
# 说明：Python 的类体会对 `__name` 形式做名称改写（name mangling），
# 因此类内部统一通过下面的单下划线别名调用，函数定义本身仍以 `__` 开头。
# ---------------------------------------------------------------------------
def __日志(消息: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {消息}", flush=True)


def __命令文本(命令: Sequence[str]) -> str:
    return subprocess.list2cmdline(list(命令))


def __环境值(环境: dict[str, str], 名称: str) -> str | None:
    """Windows 环境变量名不区分大小写。"""
    for 键, 值 in 环境.items():
        if 键.upper() == 名称.upper():
            return 值
    return None


def __执行(
    命令: Sequence[str],
    环境: dict[str, str],
    工作目录: Path,
    超时: float | None = None,
    捕获输出: bool = False,
) -> subprocess.CompletedProcess:
    __日志(f"执行: {__命令文本(命令)}")
    try:
        return subprocess.run(
            list(命令),
            cwd=str(工作目录),
            env=环境,
            check=True,
            text=True,
            encoding="mbcs" if os.name == "nt" else "utf-8",
            errors="replace",
            timeout=超时,
            capture_output=捕获输出,
        )
    except subprocess.CalledProcessError as 异常:
        if 捕获输出:
            if 异常.stdout:
                print(异常.stdout, flush=True)
            if 异常.stderr:
                print(异常.stderr, file=sys.stderr, flush=True)
        raise RuntimeError(
            f"命令执行失败，退出码 {异常.returncode}: {__命令文本(命令)}"
        ) from 异常
    except subprocess.TimeoutExpired as 异常:
        raise RuntimeError(f"命令执行超时: {__命令文本(命令)}") from 异常
    except FileNotFoundError as 异常:
        raise RuntimeError(f"找不到可执行文件: {命令[0]}") from 异常


def __安全删除(路径: Path, 根目录: Path) -> None:
    """只允许删除根目录内部的内容，避免误删项目根目录。"""
    try:
        目标 = 路径.resolve()
        根 = 根目录.resolve()
    except OSError:
        return

    if not 目标.exists():
        return
    if 目标 == 根 or 根 not in 目标.parents:
        raise RuntimeError(f"拒绝删除非构建目录: {目标}")

    if 目标.is_dir():
        shutil.rmtree(目标)
    else:
        目标.unlink()
    __日志(f"已删除: {目标}")


def __查找pyd(pyd目录: Path, 模块名: str) -> Path:
    """按当前解释器的 EXT_SUFFIX 查找模块 pyd。"""
    后缀 = sysconfig.get_config_var("EXT_SUFFIX") or ".pyd"
    精确 = pyd目录 / f"{模块名}{后缀}"
    if 精确.is_file():
        return 精确

    候选 = sorted(pyd目录.glob(f"{模块名}.*.pyd"))
    if len(候选) == 1:
        return 候选[0]

    if not 候选:
        raise RuntimeError(f"未找到 {模块名} 的 pyd，目录: {pyd目录}")
    raise RuntimeError(
        f"{模块名} 存在多个 pyd，无法确定使用哪一个:\n"
        + "\n".join(str(项) for 项 in 候选)
    )


def __收集已有pyd(配置: Config) -> list[Path]:
    return [__查找pyd(配置.pyd目录, 源文件.stem) for 源文件 in 配置.cython_targets]


def __打印用法() -> None:
    print(
        "用法:\n"
        "  python setup.py             全量：Cython -> Nuitka\n"
        "  python setup.py cython      仅生成 pyd\n"
        "  python setup.py nuitka      复用已有 pyd，仅跑 Nuitka\n"
        "  python setup.py clean       清理 build / dist / Nuitka 临时目录",
        flush=True,
    )


def __清理全部(根目录: Path) -> None:
    for 名称 in ("build", "dist", "main.build", "main.dist", "main.onefile-build"):
        __安全删除(根目录 / 名称, 根目录)


# 供类内部调用，避免名称改写
_日志 = __日志
_命令文本 = __命令文本
_环境值 = __环境值
_执行 = __执行
_安全删除 = __安全删除
_查找pyd = __查找pyd
_收集已有pyd = __收集已有pyd


# ---------------------------------------------------------------------------
# 1. Config
# ---------------------------------------------------------------------------
class Config:
    """打包参数集中控制。

    类属性是可覆盖的默认值；实例化时可通过关键字参数覆盖，例如：
        Config(jobs=8, target_arch="x86_64")
    """

    # ---- 路径 ----
    根目录 = Path(__file__).resolve().parent
    入口文件 = "main.py"
    MSVC批处理 = Path(r"D:\VS\SDK\VC\Auxiliary\Build\vcvars64.bat")
    图标路径 = Path(r"F:\app常用图片\1.ico")

    构建目录名 = "build"
    Cython子目录名 = "cython"
    输出目录名 = "dist"
    输出文件名 = "gongxueyunAuto.exe"
    Nuitka输出文件夹名: str | None = "CoreRustmatch"  # None 表示由输出文件名推导

    # ---- 资源：硬编码目标文件夹，NuitkaBuild 自动递归内部文件 ----
    资源目录定义 = [
        ("Photo", "Photo/"),
        ("data","data"),
    ]
    资源文件定义 = [
        ("Config.json", "Config.json"),
    ]

    # ---- Cython / setuptools ----
    # 入口文件由 __发现Cython目标 自动排除，无需重复列出
    Cython排除 = {"setup.py", "test_api.py"}
    # 手动填写：入口之外需要编译为 pyd 的附属 py 文件（相对根目录）
    附属py文件: list[str] = ["rust_dataclass.py"]
    Cython语言级别 = 3
    Cython并行任务数 = 22
    # setuptools 84.x 的 MSVC 默认编译参数已包含 /GL，默认链接参数已包含 /LTCG。
    # 因此 /cgthreads4 可以生效；/MP22 在“一个扩展一个 C 源文件”的场景没有收益，
    # 并行交给 build_ext --parallel。
    extra_compile_args = ["/O2", "/cgthreads4", "/utf-8"]
    extra_link_args: list[str] = []
    验证pyd导入 = True
    # Config 初始化中并行执行的任务数（MSVC 环境、版本检测、依赖包扫描）
    初始化线程数 = 3

    # ---- Nuitka ----
    msvc_version: str | None = None  # None 表示自动检测
    jobs = 22
    lto = "yes"
    mode = "standalone"
    # Nuitka 会把该值原样传给 MSVC 的 /arch:，MSVC 只识别大写 AVX2；
    # 小写 avx2 会触发 D9002（未知选项）并被忽略。
    target_arch = "AVX2"
    reproducible = "no"
    console_mode = "force"
    static_libpython = "no"
    company_name = "XIAOXING.Tech Co., Ltd."
    product_name = "gongxueyunAuto"
    file_version = "0.0.1"
    file_description = "AutoGongXueYun"

    # 由 __自动收集依赖包 扫描附属 py 的 import 自动推导；这里可追加动态导入等静态扫描不到的包
    包含包: list[str] = []
    包含包数据: list[str] = []
    # 所有 Cython 目标会自动加入；这里只放额外的、需要 Nuitka 停止跟进的模块
    不跟进模块: list[str] = []
    允许导入已排除模块 = True
    额外Nuitka参数: list[str] = []
    移除Nuitka构建缓存 = False
    # Windows standalone 首次构建需要 Dependency Walker，允许 Nuitka 自动下载到用户缓存
    允许Nuitka下载依赖 = True

    def __init__(self, **覆盖):
        # 复制可变类属性，避免不同实例之间互相污染
        self.Cython排除 = set(type(self).Cython排除)
        self.附属py文件 = list(type(self).附属py文件)
        self.extra_compile_args = list(type(self).extra_compile_args)
        self.extra_link_args = list(type(self).extra_link_args)
        self.包含包 = list(type(self).包含包)
        self.包含包数据 = list(type(self).包含包数据)
        self.不跟进模块 = list(type(self).不跟进模块)
        self.额外Nuitka参数 = list(type(self).额外Nuitka参数)
        self.资源目录定义 = list(type(self).资源目录定义)
        self.资源文件定义 = list(type(self).资源文件定义)

        for 键, 值 in 覆盖.items():
            if 键.startswith("_") or not hasattr(type(self), 键):
                raise TypeError(f"未知配置项: {键}")
            原值 = getattr(type(self), 键)
            if isinstance(原值, list):
                值 = list(值)
            elif isinstance(原值, set):
                值 = set(值)
            elif isinstance(原值, tuple):
                值 = tuple(值)
            setattr(self, 键, 值)

        self.根目录 = Path(self.根目录).resolve()
        self.MSVC批处理 = Path(self.MSVC批处理)
        self.图标路径 = Path(self.图标路径) if self.图标路径 else None
        self.构建目录 = self.根目录 / self.构建目录名
        self.Cython构建目录 = self.构建目录 / self.Cython子目录名
        self.Cython源码目录 = self.Cython构建目录 / "c"
        self.Cython临时目录 = self.Cython构建目录 / "temp"
        self.pyd目录 = self.Cython构建目录 / "pyd"
        self.输出目录 = self.根目录 / self.输出目录名
        self.资源目录 = [
            (self.根目录 / 源, 目标) for 源, 目标 in self.资源目录定义
        ]
        self.资源文件 = [
            (self.根目录 / 源, 目标) for 源, 目标 in self.资源文件定义
        ]

        self.__校验基础()
        self.cython_targets = self.__发现Cython目标()

        # MSVC 环境捕获、依赖包扫描、MSVC 版本检测互不依赖，放入线程池并行执行
        with ThreadPoolExecutor(max_workers=self.初始化线程数) as 线程池:
            环境任务 = 线程池.submit(self.__捕获MSVC环境)
            依赖任务 = 线程池.submit(self.__自动收集依赖包)
            self.msvc_env = 环境任务.result()
            版本任务 = (
                None
                if self.msvc_version
                else 线程池.submit(self.__检测MSVC版本)
            )
            if 版本任务 is not None:
                self.msvc_version = 版本任务.result()
            自动依赖包 = 依赖任务.result()

        for 源文件 in self.cython_targets:
            if 源文件.stem not in self.不跟进模块:
                self.不跟进模块.append(源文件.stem)

        self.包含包 = sorted(set(self.包含包) | set(自动依赖包))
        self.__打印摘要()

    def __校验基础(self) -> None:
        if os.name != "nt":
            raise RuntimeError("当前打包脚本仅支持 Windows")

        if self.图标路径 and not self.图标路径.is_file():
            _日志(f"警告: 图标不存在，已跳过: {self.图标路径}")
            self.图标路径 = None

    def __捕获MSVC环境(self) -> dict[str, str]:
        _日志("正在捕获 MSVC 环境变量...")

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
                ["cmd", "/c", str(临时脚本)],
                cwd=str(self.根目录),
                text=True,
                encoding="mbcs",
                errors="replace",
                capture_output=True,
            )
        finally:
            if 临时脚本 is not None:
                临时脚本.unlink(missing_ok=True)

        合并环境 = dict(os.environ)
        大小写索引 = {键.upper(): 键 for 键 in 合并环境}

        for 行 in 结果.stdout.splitlines():
            if "=" not in 行:
                continue
            键, 值 = 行.split("=", 1)
            if not 键 or 键.startswith("="):
                continue
            旧键 = 大小写索引.get(键.upper())
            if 旧键 is not None and 旧键 != 键:
                合并环境.pop(旧键, None)
            合并环境[键] = 值
            大小写索引[键.upper()] = 键

        # 强制 setuptools 使用已经激活的 MSVC 环境，避免它再次查找 vswhere
        合并环境["DISTUTILS_USE_SDK"] = "1"
        合并环境["MSSdk"] = "1"

        # 中文 Windows 下 SCons 默认用 mbcs 解码编译器输出，遇到 UTF-8 内容会报
        # UnicodeDecodeError；Nuitka 子进程使用 UTF-8 模式可规避。
        合并环境["PYTHONUTF8"] = "1"
        合并环境["PYTHONIOENCODING"] = "utf-8"

        if 结果.returncode != 0 and not 结果.stdout:
            raise RuntimeError("vcvars64.bat 执行失败，未获取到 MSVC 环境")

        PATH = _环境值(合并环境, "PATH") or ""
        if not shutil.which("cl.exe", path=PATH):
            raise RuntimeError("MSVC 环境中未找到 cl.exe，请检查 vcvars64.bat")

        _日志("MSVC 环境捕获完成")
        return 合并环境

    def __检测MSVC版本(self) -> str:
        原始 = (_环境值(self.msvc_env, "VCToolsVersion") or "").strip()
        部分 = 原始.split(".")
        try:
            主版本 = int(部分[0])
            次版本 = int(部分[1][0])  # 14.51.36231 -> 14.5
        except (ValueError, IndexError):
            _日志(f"警告: 无法解析 VCToolsVersion={原始!r}，回退 --msvc=latest")
            return "latest"

        版本 = f"{主版本}.{次版本}"
        _日志(f"检测到 MSVC 版本: {版本} (VCToolsVersion={原始})")
        return 版本

    def __发现Cython目标(self) -> list[Path]:
        排除 = self.Cython排除 | {self.入口文件}
        return [
            self.根目录 / 名称
            for 名称 in self.附属py文件
            if Path(名称).name not in 排除
        ]

    def __自动收集依赖包(self) -> list[str]:
        """扫描附属 py 的顶层 import，推导 Nuitka 需要显式包含的第三方包。"""
        本地模块 = {源文件.stem for 源文件 in self.cython_targets}
        本地模块.add(Path(self.入口文件).stem)
        名称集合: set[str] = set()

        for 源文件 in self.cython_targets:
            语法树 = ast.parse(
                源文件.read_text(encoding="utf-8"), filename=str(源文件)
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
                if importlib.util.find_spec(名称) is None:
                    continue
            except (ImportError, ValueError, AttributeError):
                continue
            包列表.append(名称)

        _日志("自动收集依赖包: " + (", ".join(包列表) if 包列表 else "(无)"))
        return 包列表

    def __打印摘要(self) -> None:
        _日志(f"项目根目录: {self.根目录}")
        _日志(f"入口文件: {self.入口文件}")
        _日志(f"MSVC 版本: {self.msvc_version}")
        _日志("Cython 目标: " + ", ".join(项.name for 项 in self.cython_targets))
        _日志("Nuitka 包含包: " + (", ".join(self.包含包) if self.包含包 else "(无)"))
        _日志(
            "Nuitka 包含包数据: "
            + (", ".join(self.包含包数据) if self.包含包数据 else "(无)")
        )
        _日志("Nuitka 不跟进: " + ", ".join(self.不跟进模块))
        _日志(f"pyd 输出目录: {self.pyd目录}")
        _日志(f"Nuitka 输出目录: {self.输出目录}")


# ---------------------------------------------------------------------------
# 2. CythonBuild
# ---------------------------------------------------------------------------
class CythonBuild:
    """把入口文件之外的 Python 文件编译为 pyd。"""

    def __init__(self, config: Config):
        self.config = config

    def 运行(self) -> list[Path]:
        _日志("=" * 60)
        _日志("阶段 1/2: Cython -> pyd")
        _日志("=" * 60)

        self.__清理()
        self.__准备目录()
        self.__写入构建脚本()
        self.__执行构建()
        pyd文件 = _收集已有pyd(self.config)

        if self.config.验证pyd导入:
            self.__校验导入()

        _日志(f"Cython 阶段完成，共生成 {len(pyd文件)} 个 pyd")
        return pyd文件

    def __清理(self) -> None:
        _安全删除(self.config.Cython构建目录, self.config.根目录)

    def __准备目录(self) -> None:
        for 目录 in (
            self.config.Cython源码目录,
            self.config.Cython临时目录,
            self.config.pyd目录,
        ):
            目录.mkdir(parents=True, exist_ok=True)

    def __写入构建脚本(self) -> None:
        扩展定义: list[str] = []
        for 源文件 in self.config.cython_targets:
            扩展定义.append(
                "    Extension(\n"
                f"        name={源文件.stem!r},\n"
                f"        sources=[{str(源文件)!r}],\n"
                f"        extra_compile_args={self.config.extra_compile_args!r},\n"
                f"        extra_link_args={self.config.extra_link_args!r},\n"
                "    ),"
            )

        脚本 = f'''# -*- coding: utf-8 -*-
from setuptools import Extension, setup
from Cython.Build import cythonize

extensions = [
{chr(10).join(扩展定义)}
]

extensions = cythonize(
    extensions,
    compiler_directives={{"language_level": {self.config.Cython语言级别}}},
    build_dir={str(self.config.Cython源码目录)!r},
)

setup(
    name="gongxueyunAuto-cython",
    version={self.config.file_version!r},
    packages=[],
    py_modules=[],
    ext_modules=extensions,
    script_args=[
        "build_ext",
        "--compiler=msvc",
        "--build-lib", {str(self.config.pyd目录)!r},
        "--build-temp", {str(self.config.Cython临时目录)!r},
        "--parallel", {str(self.config.Cython并行任务数)!r},
    ],
)
'''

        脚本路径 = self.config.Cython构建目录 / "_build_ext.py"
        脚本路径.write_text(脚本, encoding="utf-8")
        _日志(f"已生成构建脚本: {脚本路径}")

    def __执行构建(self) -> None:
        脚本路径 = self.config.Cython构建目录 / "_build_ext.py"
        _执行(
            [sys.executable, str(脚本路径)],
            环境=self.config.msvc_env,
            工作目录=self.config.根目录,
        )

    def __校验导入(self) -> None:
        if not self.config.cython_targets:
            return

        _日志("正在校验 pyd 可导入...")
        模块名 = [源文件.stem for 源文件 in self.config.cython_targets]
        导入语句 = "; ".join(f"import {名称}" for 名称 in 模块名)
        代码 = f"import sys; sys.path.insert(0, sys.argv[1]); {导入语句}"
        _执行(
            [sys.executable, "-c", 代码, str(self.config.pyd目录)],
            环境=self.config.msvc_env,
            工作目录=self.config.根目录,
            超时=180,
            捕获输出=True,
        )
        _日志("导入校验通过: " + ", ".join(模块名))


# ---------------------------------------------------------------------------
# 3. NuitkaBuild
# ---------------------------------------------------------------------------
class NuitkaBuild:
    """使用 Nuitka standalone 打包，并把 pyd / 资源放入 dist。"""

    def __init__(self, config: Config, pyd文件: list[Path]):
        self.config = config
        self.pyd文件 = list(pyd文件)

    def 运行(self) -> Path:
        _日志("=" * 60)
        _日志("阶段 2/2: Nuitka standalone 打包")
        _日志("=" * 60)

        self.__清理输出()
        参数 = self.__构建参数()
        self.__执行打包(参数)
        return self.__检查产物()

    def __输出文件夹名(self) -> str:
        名称 = self.config.Nuitka输出文件夹名 or Path(self.config.输出文件名).stem
        # Nuitka standalone 会自动追加 .dist；显式补齐，保证产物检查路径与 Nuitka 一致
        if self.config.mode == "standalone" and not 名称.lower().endswith(".dist"):
            名称 += ".dist"
        return 名称

    def __产物目录(self) -> Path:
        return self.config.输出目录 / self.__输出文件夹名()

    def __清理输出(self) -> None:
        _安全删除(self.__产物目录(), self.config.根目录)

    def __构建参数(self) -> list[str]:
        c = self.config
        目标架构 = c.target_arch
        if 目标架构.lower() in {"sse2", "avx", "avx2", "avx512"}:
            目标架构 = 目标架构.upper()

        参数 = [
            sys.executable,
            "-m",
            "nuitka",
            f"--mode={c.mode}",
            f"--msvc={c.msvc_version}",
            f"--jobs={c.jobs}",
            f"--lto={c.lto}",
            f"--static-libpython={c.static_libpython}",
            f"--target-arch={目标架构}",
            f"--reproducible={c.reproducible}",
            f"--windows-console-mode={c.console_mode}",
            f"--company-name={c.company_name}",
            f"--product-name={c.product_name}",
            f"--file-version={c.file_version}",
            f"--file-description={c.file_description}",
            f"--output-dir={c.输出目录}",
            f"--output-folder-name={self.__输出文件夹名()}",
            f"--output-filename={c.输出文件名}",
        ]

        if c.图标路径:
            参数.append(f"--windows-icon-from-ico={c.图标路径}")
        if c.允许Nuitka下载依赖:
            参数.append("--assume-yes-for-downloads")
        if c.移除Nuitka构建缓存:
            参数.append("--remove-output")

        for 模块 in c.不跟进模块:
            参数.append(f"--nofollow-import-to={模块}")
        if c.允许导入已排除模块 and c.不跟进模块:
            参数.append("--no-deployment-flag=excluded-module-usage")
        for 包 in c.包含包:
            参数.append(f"--include-package={包}")
        for 包 in c.包含包数据:
            参数.append(f"--include-package-data={包}")

        # 资源目录：硬编码源目录，自动递归内部所有文件并保留目录结构
        for 源目录, 目标目录 in c.资源目录:
            目标前缀 = 目标目录.strip("/\\")
            参数.append(f"--include-data-files={源目录}={目标前缀}/=**/*")
        for 源文件, 目标文件 in c.资源文件:
            参数.append(f"--include-data-files={源文件}={目标文件}")
        for pyd in self.pyd文件:
            参数.append(f"--include-data-files={pyd}={pyd.name}")

        参数.extend(c.额外Nuitka参数)
        参数.append(str(c.根目录 / c.入口文件))
        return 参数

    def __执行打包(self, 参数: list[str]) -> None:
        _执行(
            参数,
            环境=self.config.msvc_env,
            工作目录=self.config.根目录,
        )

    def __检查产物(self) -> Path:
        产物目录 = self.__产物目录()
        可执行文件 = 产物目录 / self.config.输出文件名
        if not 可执行文件.is_file():
            现有 = (
                sorted(str(项) for 项 in self.config.输出目录.iterdir())
                if self.config.输出目录.is_dir()
                else []
            )
            raise RuntimeError(
                f"未找到可执行文件: {可执行文件}\n"
                f"输出目录内容: {现有}"
            )

        缺失: list[str] = []

        for pyd in self.pyd文件:
            if not (产物目录 / pyd.name).is_file():
                缺失.append(pyd.name)

        for _源文件, 目标文件 in self.config.资源文件:
            if not (产物目录 / 目标文件).is_file():
                缺失.append(目标文件)

        for 源目录, 目标目录 in self.config.资源目录:
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

        _日志("Nuitka 打包完成")
        _日志(f"产物目录: {产物目录}")
        _日志(f"可执行文件: {可执行文件}")
        return 可执行文件


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------
def main(argv: list[str] | None = None) -> int:
    try:
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        if hasattr(sys.stderr, "reconfigure"):
            sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    参数 = list(sys.argv[1:] if argv is None else argv)
    if not 参数:
        模式 = "all"
    elif len(参数) == 1:
        模式 = 参数[0].lower()
    else:
        __打印用法()
        return 2

    if 模式 in {"-h", "--help", "help"}:
        __打印用法()
        return 0

    if 模式 == "clean":
        try:
            __清理全部(Path(__file__).resolve().parent)
        except Exception as 异常:
            __日志(f"清理失败: {异常}")
            return 1
        __日志("清理完成")
        return 0

    if 模式 not in {"all", "cython", "nuitka"}:
        __打印用法()
        return 2

    try:
        配置 = Config()

        pyd文件: list[Path] = []
        if 模式 in {"all", "cython"}:
            pyd文件 = CythonBuild(配置).运行()

        if 模式 in {"all", "nuitka"}:
            if not pyd文件:
                pyd文件 = __收集已有pyd(配置)
            NuitkaBuild(配置, pyd文件).运行()

    except Exception as 异常:
        __日志(f"构建失败: {异常}")
        traceback.print_exc()
        return 1

    __日志("全部完成")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
