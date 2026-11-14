# -*- coding: utf-8 -*-
import os
import subprocess
from _dataclass import 程序路径,_功能函数
import ExpandGXYxiaoxing



TESSERACT路径 = 程序路径 / "tesseract" / "tesseract.exe"
TESSDATA路径 = 程序路径 / "tesseract" / "tessdata"

def 光学文字识别(
    图片: bytes,
    目标字符串:str,
    置信度:float=0.5,
    语言: str = "chi_sim+eng",
    psm: int = 11,
    超时秒数: float =  60,
) -> dict[str, list[int]]:
    """识别二进制图片，返回 ``{文字: [left, top]}``。

    参数:
        图片: 图片文件原始字节（PNG/JPG/BMP 等，Tesseract 自动识别格式）。
        语言: Tesseract 语言，多个用 ``+`` 连接，默认 ``chi_sim+eng``。
        psm: 页面分割模式，默认 11（稀疏文字，适合界面截图）。
        超时秒数: 子进程超时时间。

    返回:
        key 为识别到的文字，value 为文字左上角的 ``[x, y]`` 像素坐标。
        同一文字重复出现时保留最后一条。
    """
    if not TESSERACT路径.is_file():
        raise FileNotFoundError(f"找不到 Tesseract: {TESSERACT路径}")
    if not TESSDATA路径.is_dir():
        raise FileNotFoundError(f"找不到 tessdata: {TESSDATA路径}")
    图片=_功能函数.图片解码(图片)
    命令 = [
        str(TESSERACT路径),
        "stdin",
        "stdout",
        "-l",
        语言,
        "--psm",
        str(psm),
        "--oem",
        "2",
        "--tessdata-dir",
        str(TESSDATA路径),
        "tsv",
    ]
    运行参数 = {
        "input": 图片,
        "timeout": 超时秒数,
        "check": False,
    }
    if os.name == "nt":
        运行参数["creationflags"] = subprocess.CREATE_NO_WINDOW

    结果 = subprocess.run(命令, **运行参数,capture_output=True)
    if 结果.returncode != 0:
        错误 = 结果.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"Tesseract 识别失败（退出码 {结果.returncode}）: {错误}")

    return ExpandGXYxiaoxing.tesseract_process(结果.stdout,目标字符串,0,1.0,置信度)


def test_OCR():
    with open(程序路径 / "OCRtest.jpg", "rb") as f:

        print(光学文字识别(f.read(),"非常轻松,有大量空闲时间",0.85,超时秒数=120))