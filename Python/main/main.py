import uvicorn
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from OCR_Core import 光学文字识别
from _dataclass import See数据,提交图片_数据,fastapiObj,程序路径,OCR识别_提交数据,AI文本
from Function_One import Function_One
from 装饰器 import 线程池执行


@fastapiObj.post("/Register")
@线程池执行
async def 注册(请求信息:Request):

    if 请求信息.headers.get("Headers") != "XIAOXING":
        return JSONResponse(status_code=500, content={"message":"请求头不合法"})

    obj=JSONResponse(content={"message":"注册成功"},status_code=200)
    obj.set_cookie(
        key="CookieAll",
        value=Function_One.GCM_Aes128密钥生成(),
        max_age=Function_One.有效期秒数,
        httponly=True,
        secure=True,
        samesite="lax",
        path="/See"
    )
    return obj

@fastapiObj.post("/See")
@线程池执行
async def 模板匹配(用户数据:See数据):
    try:
        if  not Function_One.GCM_Aes128解密(用户数据.Cookie密钥) :
            return [401,{"message":"SB啊接这个干嘛"}]

        模板图片=Function_One.读取模板(用户数据.识别对象)
        位置信息=Function_One.模板匹配(匹配图片=用户数据.图片base64,模板图片=模板图片,置信度=用户数据.置信度)
        return JSONResponse(status_code=200,content={"result":位置信息})

    except Exception as e:
        print(str(e) + " / " +用户数据.识别对象)
        return Response(status_code=500)



@fastapiObj.post("/See_Submit_Photo")
@线程池执行
async def 模板匹配_提交图片(用户数据:提交图片_数据):

    try:
        if not Function_One.GCM_Aes128解密(用户数据.Cookie密钥):
            return JSONResponse(status_code=401,content={"message":"SB啊接这个干嘛"})
        位置信息= Function_One.模板匹配2(匹配图片_检测=用户数据.识别图片,匹配图片_模板=用户数据.模板图片,置信度=用户数据.置信度)
        return JSONResponse(status_code=200,content={"result":位置信息})

    except Exception as e :
        print("See_Submit_Photo匹配图片失败" + str(e))
        return Response(status_code=500)

@fastapiObj.post("/AiText")
@线程池执行
async def AI文案(用户数据:AI文本):
    try:

        if not Function_One.GCM_Aes128解密(用户数据.Cookie密钥):
            return JSONResponse(status_code=401, content={"message": "SB啊接这个干嘛"})

        系统提示词 = (程序路径 / "data" / "SOUL.md").read_text(encoding="utf-8")
        内容=Function_One.AI文案(提示词=用户数据.提示词,系统提示词=系统提示词)
        return JSONResponse(status_code=200,content={"result":内容})

    except Exception as e :
        print("AiText"+str(e))
        return Response(status_code=500)


@fastapiObj.post("/GetMatchName")
@线程池执行
async def 获取模板名称(Cookie:str):

        if not Function_One.GCM_Aes128解密(Cookie):
                return JSONResponse(status_code=401,content={"message": "SB啊接这个干嘛"})
        return  JSONResponse(status_code=200,content={"result":Function_One.文件名称})


@fastapiObj.post("/UpdataPhotoName")
@线程池执行
async def 更新模板名称(Cookie:str):
    try:
        if not Function_One.GCM_Aes128解密(Cookie):
                return JSONResponse(status_code=401,content={"message": "SB啊接这个干嘛"})

        名称列表 = Function_One.获取文件名称(程序路径 / "Photo")
        return JSONResponse(status_code=200, content={"result": 名称列表})
    except Exception as e :
        print(e)
        return Response(status_code=500)


@fastapiObj.post("/OCRPhoto")
@线程池执行
async def OCR(用户数据:OCR识别_提交数据):

    try:
        if not Function_One.GCM_Aes128解密(用户数据.Cookie密钥):
            return JSONResponse(status_code=401, content={"message": "SB啊接这个干嘛"})

        结果=光学文字识别(图片=用户数据.识别图片,目标字符串=用户数据.目标文字,置信度=用户数据.置信度,psm=用户数据.PSM,超时秒数=120)

        return  JSONResponse(status_code=200,content={"result":结果})
    except Exception as e:
        print(str(e) + " /  OCRPhoto" )
        return Response(status_code=500)


if __name__=="__main__":
    错误列表 = Function_One.初始化程序()
    for 错误 in 错误列表:
        print(f"[初始化] {错误}")

    if Function_One.配置数据 is None:
        raise SystemExit("Config.json 读取失败，服务未启动")

    uvicorn.run(app=fastapiObj,host=Function_One.配置数据.get("IP"),port=Function_One.配置数据.get("Port"))