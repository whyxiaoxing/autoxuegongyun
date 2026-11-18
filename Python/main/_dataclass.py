
from fastapi import FastAPI
from pydantic import BaseModel
import fastapi
from concurrent.futures.thread import ThreadPoolExecutor
from pathlib import Path

fastapiObj: FastAPI=fastapi.FastAPI()
程序路径 =  Path(__file__).resolve().parent
线程池 = ThreadPoolExecutor(max_workers=10)


class See数据(BaseModel):
    图片base64:bytes
    识别对象:str
    Cookie密钥:str
    置信度:float

class 提交图片_数据(BaseModel):
    模板图片:bytes
    识别图片:bytes
    Cookie密钥:str
    置信度: float

class OCR识别_提交数据(BaseModel):
    识别图片:bytes
    目标文字:str
    置信度:float
    PSM:int
    Cookie密钥:str
class AI文本(BaseModel):
    提示词: str
    Cookie密钥: str

def 读取模板图片(图片路径: Path) -> bytes:
    if not 图片路径.is_file():
        raise FileNotFoundError(f"模板图片不存在: {图片路径}")
    图片字节 = 图片路径.read_bytes()
    if not 图片字节:
        raise ValueError(f"模板图片为空: {图片路径}")
    return 图片字节

