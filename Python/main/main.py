import uvicorn
from fastapi import Body
from starlette.requests import Request
from starlette.responses import JSONResponse

from OCR_Core import 光学文字识别
from _dataclass import _功能函数,See数据,提交图片_数据,fastapiObj,程序路径,OCR识别_提交数据


@fastapiObj.post("/Register")
def 注册(请求信息:Request):

    if 请求信息.headers.get("Headers") != "XIAOXING":
        return JSONResponse(status_code=500, content={"message":"请求头不合法"})

    obj=JSONResponse(content={"message":"注册成功"},status_code=200)
    obj.set_cookie(
        key="CookieAll",
        value=_功能函数.GCM_Aes128密钥生成(),
        max_age=_功能函数.有效期秒数,
        httponly=True,
        secure=True,
        samesite="lax",
        path="/See"
    )
    return obj

@fastapiObj.post("/See")
def 模板匹配(用户数据:See数据):
    try:
        if  not _功能函数.GCM_Aes128解密(用户数据.Cookie密钥) :
            return JSONResponse(status_code=401,content={"message":"SB啊接这个干嘛"})

        # 图片= _功能函数.解码(用户数据.图片base64)
        print(用户数据.识别对象)
        模板图片=_功能函数.读取模板(用户数据.识别对象)
        位置信息=_功能函数.模板匹配(匹配图片=用户数据.图片base64,模板图片=模板图片,置信度=用户数据.置信度)
        return JSONResponse(status_code=200,content={"result":位置信息})

    except Exception as e:
        return JSONResponse(status_code=500, content={"message":str(e)})

@fastapiObj.post("/See_Submit_Photo")
def 模板匹配_提交图片(用户数据:提交图片_数据):

    try:
        if not _功能函数.GCM_Aes128解密(用户数据.Cookie密钥):
            return JSONResponse(status_code=401,content={"message":"SB啊接这个干嘛"})
        位置信息= _功能函数.模板匹配2(匹配图片_检测=用户数据.识别图片,匹配图片_模板=用户数据.模板图片,置信度=用户数据.置信度)
        return JSONResponse(status_code=200,content={"result":位置信息})

    except Exception as e :
        return JSONResponse(status_code=500, content={"message":str(e)})

@fastapiObj.post("/AiText")
def AI文案(提示词:str,Cookie密钥:str):
    try:

        if not _功能函数.GCM_Aes128解密(Cookie密钥):
            return JSONResponse(status_code=401, content={"message": "SB啊接这个干嘛"})
        系统提示词 = (程序路径 / "data" / "SOUL.md").read_text(encoding="utf-8")
        内容=_功能函数.AI文案(提示词=提示词,系统提示词=系统提示词)
        return JSONResponse(status_code=200,content={"result":内容})

    except Exception as e :
        return JSONResponse(status_code=500, content={"message":str(e)})


@fastapiObj.post("/GetMatchName")
async def 获取模板名称(Cookie:str):

        if not _功能函数.GCM_Aes128解密(Cookie):
                return JSONResponse(status_code=401,content={"message": "SB啊接这个干嘛"})
        return  JSONResponse(status_code=200,content={"result":_功能函数.文件名称})


@fastapiObj.post("/UpdataPhotoName")
def 更新模板名称(Cookie:str):
    try:
        if not _功能函数.GCM_Aes128解密(Cookie):
                return JSONResponse(status_code=401,content={"message": "SB啊接这个干嘛"})

        名称列表 = _功能函数.获取文件名称(程序路径 / "Photo")
        return JSONResponse(status_code=200, content={"result": 名称列表})
    except Exception as e :
        return JSONResponse(status_code=500, content={"message":str(e)})


@fastapiObj.post("/OCRPhoto")
def OCR(用户数据:OCR识别_提交数据):

    try:
        if not _功能函数.GCM_Aes128解密(用户数据.Cookie密钥):
            return JSONResponse(status_code=401, content={"message": "SB啊接这个干嘛"})

        结果=光学文字识别(图片=用户数据.识别图片,目标字符串=用户数据.目标文字,置信度=用户数据.置信度,psm=用户数据.PSM,超时秒数=120)

        return  JSONResponse(status_code=200,content={"result":结果})
    except Exception as e:
        return JSONResponse(status_code=500, content={"message": str(e)})


if __name__=="__main__":
    错误列表 = _功能函数.初始化程序()
    for 错误 in 错误列表:
        print(f"[初始化] {错误}")

    if _功能函数.配置数据 is None:
        raise SystemExit("Config.json 读取失败，服务未启动")

    uvicorn.run(app=fastapiObj,host=_功能函数.配置数据.get("IP"),port=_功能函数.配置数据.get("Port"))