import base64
import json
import os
import threading
import time
from concurrent.futures.thread import ThreadPoolExecutor
from pathlib import Path
from typing import Optional, Tuple
import cv2
import fastapi
import numpy
import requests
from Crypto.Cipher import AES
from fastapi import FastAPI
from pydantic import BaseModel


class _Fastapidata:
    def __init__(self):
        self.fastapiObj: FastAPI=fastapi.FastAPI()
        self.程序路径 =  Path(__file__).resolve().parent

class 功能函数:
    def __init__(self):
        self.密文="XIAOXING"
        self.key=os.urandom(16)
        self.配置数据:dict[str,str]=None
        self.__模板表: dict[str, numpy.ndarray] = {}
        self.相似度阈值: float = 0.9
        self.有效期秒数: int = 1300
        self.密钥更新秒数: int = 30 * 60
        self.线程池 = ThreadPoolExecutor(max_workers=10)
        threading.Thread(target=self.__定时更新密钥, daemon=True, name="CookieKeyRotator").start()

    def 生成Cookie(self):
        return  self.__GCM_Aes128密钥生成()

    def 解密Cookie对象(self,密文):
        return  self.__GCM_Aes128解密(密文)

    def 初始化程序(self) -> list[str]:
        self.__模板表.clear()
        错误: list[str] = []
        配置成功 = True

        try:
            self.__读取配置()
        except Exception as 异常:
            配置成功 = False
            错误.append(f"读取配置: {异常}")

        任务 = {
            "加载模板": self.线程池.submit(
                加载模板, type(_模板识别数据), _Fastapidata.程序路径 / "Photo", self.__模板表, self.线程池
            ),
        }
        if 配置成功:
            任务["AI模型初始化"] = self.线程池.submit(self.__AI模型初始化)

        for 名称, 任务对象 in 任务.items():
            try:
                结果 = 任务对象.result()
                if 名称 == "AI模型初始化" and not 结果:
                    错误.append("AI模型初始化: AI 服务不可达")
            except Exception as 异常:
                错误.append(f"{名称}: {异常}")

        return 错误

    def __定时更新密钥(self) -> None:
        while True:
            time.sleep(self.密钥更新秒数)
            self.key = os.urandom(16)

    def 模板匹配(self,匹配图片: numpy.ndarray, 模板图片: numpy.ndarray) -> Optional[Tuple[int, int]]:

        匹配结果 = cv2.matchTemplate(匹配图片, 模板图片, cv2.TM_CCOEFF_NORMED)

        # 结果矩阵中的最小值   结果矩阵中的最大值   最小值所在的坐标（列, 行）    最大值所在的坐标（列, 行）
        _, 最大相似度, _, 最大坐标 = cv2.minMaxLoc(匹配结果)

        return 最大坐标 if 最大相似度 > self.相似度阈值 else None

    def AI文案(self, 提示词: str, 系统提示词: str = "你是一个专业的文案写作助手，请按用户要求生成文案，只输出文案内容。") -> str:
        提供方=self.配置数据["APItype"].strip().lower()
        headerContext = {
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        if 提供方 != "ollama":
            apiKey = self.配置数据["ApiKey"].strip()
            if apiKey and not apiKey.lower().startswith("bearer "):
                apiKey = f"Bearer {apiKey}"
            headerContext["Authorization"] = apiKey

        return self.__AI请求( self.配置数据["AIURL"], headerContext, 提示词, 系统提示词)

    def 解码(self,数据:bytes)->numpy.ndarray:
        二进制 = base64.b64decode(数据)
        uint数组= numpy.frombuffer(二进制,dtype=numpy.uint8)
        解码图片 = cv2.imdecode(uint数组,cv2.IMREAD_COLOR)
        if 解码图片 is None:
            raise ValueError("图片解码失败")
        return  解码图片

    def 获取模板(self, 名称: str) -> numpy.ndarray:
        try:
            return self.__模板表[名称]
        except KeyError:
            raise ValueError(f"未知的识别对象: {名称}") from None

    def __AI模型初始化(self) -> bool:

        URL = str(self.配置数据.get("AIURL", "")).strip()
        try:
            响应 = requests.get(URL, timeout=10, stream=True)
            响应.close()
            return True
        except requests.exceptions.Timeout:
            return False
        except requests.exceptions.ConnectionError:
            return False
        except requests.exceptions.RequestException:
            return False

    def __读取配置(self):
        json路径=_Fastapidata.程序路径 / "Config.json"
        with open(json路径,mode="r",encoding="utf_8") as f:
            self.配置数据 = json.load(f)


    def __GCM_Aes128密钥生成(self) -> str:
        nonce = os.urandom(12)
        加密后数据=AES.new(key=self.key,nonce=nonce,mode=AES.MODE_GCM)
        明文 = f"{self.密文}:{int(time.time())}".encode()
        ct,tag = 加密后数据.encrypt_and_digest(明文)
        return  base64.b64encode(nonce + tag + ct).decode("utf-8")

    def __GCM_Aes128解密(self,密文b64: str) -> bool:
        try:
            raw = base64.b64decode(密文b64)
            nonce, tag, ct = raw[:12], raw[12:28], raw[28:]
            解密器 = AES.new(self.key, AES.MODE_GCM, nonce=nonce)
            解密结果 = 解密器.decrypt_and_verify(ct, tag).decode("utf-8")
            标记, 签发时间 = 解密结果.rsplit(":", 1)
            时差 = time.time() - int(签发时间)
            return 标记 == self.密文 and 0 <= 时差 <= self.有效期秒数
        except Exception :
            return False
    def __AI请求(self, URL: str, 请求头: dict, 提示词: str, 系统提示词: str) -> str:

        请求体 = {
            "model": self.配置数据.get("Model", ""),
            "messages": [
                {"role": "system", "content": 系统提示词},
                {"role": "user", "content": 提示词},
            ],
            "stream": False,
        }

        try:
            响应 = requests.post(URL, headers=请求头, json=请求体, timeout=600)
            结果 = 响应.json()
            内容 = ((结果.get("choices") or [{}])[0].get("message") or {}).get("content", "")
            if isinstance(内容, str):
                return 内容.strip()
            return json.dumps(内容, ensure_ascii=False)
        except Exception:
            raise


class 模板识别数据:

    class 功能:
        登录:Optional[numpy.ndarray]=None
        同意圆圈:Optional[numpy.ndarray]=None
        签到图标:Optional[numpy.ndarray]=None
        全部: Optional[numpy.ndarray] = None
        添加图片: Optional[numpy.ndarray] = None

    class 每日签到:
        手动点击位置_1: Optional[numpy.ndarray] = None
        手动点击位置_2: Optional[numpy.ndarray] = None
        上传图片: Optional[numpy.ndarray] = None

    class 报告:
        日报:Optional[numpy.ndarray]=None
        周报:Optional[numpy.ndarray]=None
        月报:Optional[numpy.ndarray]=None

        class 主体:
            内容:Optional[numpy.ndarray]=None
            日报标题:Optional[numpy.ndarray]=None
            月报标题:Optional[numpy.ndarray]=None
            提交:Optional[numpy.ndarray]=None


_Fastapidata = _Fastapidata()
_功能函数 = 功能函数()
_模板识别数据 = 模板识别数据()



class See数据(BaseModel):
    图片base64:bytes
    识别对象:str
    Cookie密钥:str









def 模板图片转BGR(图片: numpy.ndarray, 图片路径: Path) -> numpy.ndarray:
    if 图片.dtype != numpy.uint8:
        raise ValueError(f"模板图片仅支持 8 位图: {图片路径}")

    if 图片.ndim == 2:
        return numpy.ascontiguousarray(cv2.cvtColor(图片, cv2.COLOR_GRAY2BGR))
    if 图片.ndim != 3:
        raise ValueError(f"不支持的模板图片维度: {图片.ndim} ({图片路径})")

    通道数 = 图片.shape[2]
    if 通道数 == 3:
        return numpy.ascontiguousarray(图片)

    if 通道数 == 4:
        前景 = cv2.cvtColor(图片, cv2.COLOR_BGRA2BGR).astype(numpy.float32)
        透明度 = 图片[:, :, 3].astype(numpy.float32) / 255.0
    elif 通道数 == 2:
        灰度 = numpy.ascontiguousarray(图片[:, :, 0])
        前景 = cv2.cvtColor(灰度, cv2.COLOR_GRAY2BGR).astype(numpy.float32)
        透明度 = 图片[:, :, 1].astype(numpy.float32) / 255.0
    else:
        raise ValueError(f"不支持的模板图片通道数: {通道数} ({图片路径})")

    透明度 = 透明度[:, :, None]
    合成 = 前景 * 透明度 + 255.0 * (1.0 - 透明度)
    return numpy.ascontiguousarray(numpy.rint(numpy.clip(合成, 0.0, 255.0)).astype(numpy.uint8))


def 读取模板图片(图片路径: Path) -> numpy.ndarray:
    if not 图片路径.is_file():
        raise FileNotFoundError(f"模板图片不存在: {图片路径}")
    图片 = cv2.imdecode(numpy.fromfile(图片路径, dtype=numpy.uint8), cv2.IMREAD_UNCHANGED)
    if 图片 is None:
        raise ValueError(f"模板图片无法解析: {图片路径}")
    return 模板图片转BGR(图片, 图片路径)


def 加载模板(
    类对象: type,
    图片根目录: Path,
    模板表: dict[str, numpy.ndarray],
    线程池: ThreadPoolExecutor,
) -> None:
    # 迭代遍历嵌套类，不递归
    待遍历 = [类对象]
    模板定义: list[tuple[type, str]] = []
    已收集: set[str] = set()

    while 待遍历:
        当前类 = 待遍历.pop()
        for 变量值 in vars(当前类).values():
            if isinstance(变量值, type):
                待遍历.append(变量值)
        for 变量名称 in vars(当前类).get("__annotations__", {}):
            if 变量名称 in 模板表 or 变量名称 in 已收集:
                raise ValueError(f"模板名称重复: {变量名称}")
            已收集.add(变量名称)
            模板定义.append((当前类, 变量名称))

    # 每张模板图片独立解码，直接并发读取
    任务 = [
        (类, 变量名称, 线程池.submit(读取模板图片, 图片根目录 / f"{变量名称}.png"))
        for 类, 变量名称 in 模板定义
    ]

    错误: list[str] = []
    for 类, 变量名称, 任务对象 in 任务:
        try:
            图片 = 任务对象.result()
        except Exception as 异常:
            错误.append(f"{变量名称}: {异常}")
            continue
        setattr(类, 变量名称, 图片)
        模板表[变量名称] = 图片

    if 错误:
        raise ValueError("模板加载失败:\n" + "\n".join(错误))