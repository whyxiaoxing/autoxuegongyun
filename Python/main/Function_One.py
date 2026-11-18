import base64
import json
import os
import threading
import time
from pathlib import Path
from typing import Optional, Tuple
import requests
from Crypto.Cipher import AES
import  rustmatch
from rustmatch import MatchResult
from _dataclass import 程序路径,线程池


class 功能函数:
    def __init__(self):
        self.密文="XIAOXING"
        self.key=os.urandom(16)
        self.配置数据:dict[str,str]=  None
        self.有效期秒数: int = 1300
        self.密钥更新秒数: int = 30 * 60

        threading.Thread(target=self.__定时更新密钥, daemon=True, name="CookieKeyRotator").start()
        self.文件名称=[]

    def 初始化程序(self) -> list[str]:
        错误: list[str] = []
        配置成功 = True


        任务 = {
            # 模板扫描不依赖配置，配置读取失败时也要执行
            "模板扫描": 线程池.submit(self.获取文件名称, 程序路径 / "Photo"),
            "配置读取": 线程池.submit(self.__读取配置,)
        }
        if 配置成功:
            任务["OCR混淆设置"] = 线程池.submit(self.__OCR模糊匹配字体设置)
            任务["AI模型初始化"] = 线程池.submit(self.__AI模型初始化)

        for 名称, 任务对象 in 任务.items():
            try:
                结果 = 任务对象.result()
            except Exception as 异常:
                错误.append(f"{名称}: {异常}")
                continue

            if 名称 == "AI模型初始化" and not 结果:
                错误.append("AI模型初始化: AI 服务不可达")

        return 错误

    def 模板匹配(self,匹配图片: bytes, 模板图片:bytes,置信度:float=0.7) -> Optional[Tuple[int, int]]:

        匹配图片=self.图片解码(匹配图片)
        结果: MatchResult | None=rustmatch.find_bytes(source=匹配图片,template=模板图片,threshold=置信度)

        if 结果 is None:
            raise  Exception("没有找到目标位置")
        print(结果.confidence)
        # 结果.x 结果.y  左上角的xy位置
        return 结果.x + (结果.matched_width >> 1), 结果.y + (结果.matched_height >> 1)

    def 模板匹配2(self,匹配图片_检测:bytes, 匹配图片_模板:bytes,置信度:float=0.7):

        匹配图片_检测=self.图片解码(匹配图片_检测)
        匹配图片_模板=self.图片解码(匹配图片_模板)
        结果: MatchResult | None = rustmatch.find_bytes(source=匹配图片_检测,template=匹配图片_模板,threshold=置信度)

        if 结果 is None:
            raise  Exception("没有找到目标位置")
        # 结果.x 结果.y  左上角的xy位置
        return 结果.x + (结果.matched_width >> 1), 结果.y + (结果.matched_height >> 1)

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


    def 读取模板(self, 名称: str) -> bytes:
        # 识别对象来自请求，禁止路径分隔符，避免越权读取 Photo 之外的文件
        if not 名称 or 名称 in (".", "..") or any(字符 in 名称 for 字符 in '\\/:*?"<>|'):
            raise FileNotFoundError(f"未知的识别对象: {名称}")

        路径: Path = 程序路径 / "Photo" / f"{名称}.png"
        try:
            return 路径.read_bytes()
        except FileNotFoundError:
            raise FileNotFoundError(f"未知的识别对象: {名称}") from None

    def 获取文件名称(self, 文件路径: Path) -> list[str]:
        if not 文件路径.is_dir():
            raise NotADirectoryError(f"模板目录不存在: {文件路径}")

        # 只扫描当前目录，不递归子目录；Windows 同目录文件名唯一（不区分大小写），无需去重
        self.文件名称 = sorted(
            (文件.stem for 文件 in 文件路径.glob("*.png") if 文件.is_file()),
            key=str.casefold,
        )
        return list(self.文件名称)

    def GCM_Aes128密钥生成(self) -> str:
        nonce = os.urandom(12)
        加密后数据 = AES.new(key=self.key, nonce=nonce, mode=AES.MODE_GCM)
        明文 = f"{self.密文}:{int(time.time())}".encode()
        ct, tag = 加密后数据.encrypt_and_digest(明文)
        return base64.b64encode(nonce + tag + ct).decode("utf-8")

    def GCM_Aes128解密(self, 密文b64: str) -> bool:
        try:
            raw = base64.b64decode(密文b64)
            nonce, tag, ct = raw[:12], raw[12:28], raw[28:]
            解密器 = AES.new(self.key, AES.MODE_GCM, nonce=nonce)
            解密结果 = 解密器.decrypt_and_verify(ct, tag).decode("utf-8")
            标记, 签发时间 = 解密结果.rsplit(":", 1)
            时差 = time.time() - int(签发时间)
            return 标记 == self.密文 and 0 <= 时差 <= self.有效期秒数
        except Exception:
            return False

    def 图片解码(self,图片:bytes) -> bytes:
        return  base64.b64decode(图片)

    def __定时更新密钥(self) -> None:
        while True:
            time.sleep(self.密钥更新秒数)
            self.key = os.urandom(16)

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

    def __OCR模糊匹配字体设置(self) -> None:
        from ExpandGXYxiaoxing import set_confusion

        # 配置结构: {"目标字符": ["易混字符", ...]}，关系有向，逐条注册到 OCR 匹配器
        映射 = (self.配置数据 or {}).get("wenziyingshe") or {}
        if not isinstance(映射, dict):
            raise ValueError("配置项 wenziyingshe 必须是对象：{字符: [易混字符]}")

        for 目标字符, 混淆字符列表 in 映射.items():
            if not isinstance(目标字符, str) or len(目标字符) != 1:
                raise ValueError(f"wenziyingshe 的键必须是单个字符: {目标字符!r}")
            if not isinstance(混淆字符列表, list) or not 混淆字符列表:
                raise ValueError(f"wenziyingshe 中 {目标字符!r} 的混淆字符必须是非空数组")

            for 混淆字符 in 混淆字符列表:
                if not isinstance(混淆字符, str) or len(混淆字符) != 1:
                    raise ValueError(
                        f"wenziyingshe 中 {目标字符!r} 的混淆字符必须是单个字符: {混淆字符!r}"
                    )

            set_confusion(目标字符, list(混淆字符列表))



    def __读取配置(self):
        json路径=程序路径 / "Config.json"
        with open(json路径,mode="r",encoding="utf_8") as f:
            self.配置数据 = json.load(f)



    def __AI请求(self, URL: str, 请求头: dict, 提示词: str, 系统提示词: str) -> str:

        请求体 = {
            "model": self.配置数据.get("Model", ""),
            "messages": [
                {"role": "system", "content": 系统提示词},
                {"role": "user", "content": 提示词},
            ],
            "stream": False,
            "think": False,
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


Function_One = 功能函数()