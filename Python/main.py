import uvicorn
from starlette.requests import Request
from starlette.responses import Response, JSONResponse
from rust_dataclass import _Fastapidata,_功能函数,See数据,提交图片_数据


@_Fastapidata.fastapiObj.post("/Register")
def 注册(请求信息:Request):

    if 请求信息.headers.get("Headers") != "XIAOXING":
        return JSONResponse(status_code=500, content={"message":"请求头不合法"})

    obj=JSONResponse(content={"message":"注册成功"},status_code=200)
    obj.set_cookie(
        key="CookieAll",
        value=_功能函数.生成Cookie(),
        max_age=_功能函数.有效期秒数,
        httponly=True,
        secure=True,
        samesite="lax",
        path="/See"
    )
    return obj

@_Fastapidata.fastapiObj.post("/See")
def 模板匹配(用户数据:See数据):
    try:
        if  not _功能函数.解密Cookie对象(用户数据.Cookie密钥) :
            return JSONResponse(status_code=401,content={"message":"SB啊接这个干嘛"})

        # 图片= _功能函数.解码(用户数据.图片base64)
        模板图片=_功能函数.获取模板(用户数据.识别对象)
        位置信息=_功能函数.模板匹配(匹配图片=用户数据.图片base64,模板图片=模板图片)
        return JSONResponse(status_code=200,content={"result":位置信息})

    except Exception as e:
        return JSONResponse(status_code=500, content={"message":str(e)})

@_Fastapidata.fastapiObj.post("/See_Submit_Photo")
def 模板匹配_提交图片(用户数据:提交图片_数据):

    try:
        if not _功能函数.解密Cookie对象(用户数据.Cookie密钥):
            return JSONResponse(status_code=401,content={"message":"SB啊接这个干嘛"})
        位置信息= _功能函数.模板匹配2(匹配图片_检测=用户数据.识别图片,匹配图片_模板=用户数据.模板图片)
        return JSONResponse(status_code=200,content={"result":位置信息})

    except Exception as e :
        return JSONResponse(status_code=500, content={"message":str(e)})

@_Fastapidata.fastapiObj.post("/AiText")
def AI文案(提示词:str,Cookie密钥:str):
    try:

        if not _功能函数.解密Cookie对象(Cookie密钥):
            return JSONResponse(status_code=401, content={"message": "SB啊接这个干嘛"})
        with open("./data/SOUL.md","r",encoding="utf-8") as f:
            SOLU=f.read()
        内容=_功能函数.AI文案(提示词=提示词,xiaoxing=SOLU)
        return JSONResponse(status_code=200,content={"result":内容})

    except Exception as e :
        return JSONResponse(status_code=500, content={"message":str(e)})

if __name__=="__main__":
 _功能函数.初始化程序()

 uvicorn.run(app=_Fastapidata.fastapiObj,host=_功能函数.配置数据.get("IP"),port=_功能函数.配置数据.get("Port"))