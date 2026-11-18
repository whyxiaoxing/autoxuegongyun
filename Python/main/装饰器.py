import asyncio
import contextvars
import inspect
from functools import wraps
from typing import Any, Callable

from _dataclass import 线程池


def 线程池执行(处理_内容: Callable[..., Any]) -> Callable[..., Any]:
    """
    把路由处理函数提交到 _dataclass.线程池 执行，事件循环只负责等待结果。
    原函数是 async def 或 def 都可以；FastAPI 仍按原函数签名解析参数。

    用法：
        @fastapiObj.post("/xxx")
        @线程池执行
        async def 处理(用户数据: 某数据):
            ...
    """

    if inspect.iscoroutinefunction(处理_内容):
        def 同步调用(*参数: Any, **关键字参数: Any) -> Any:
            # 工作线程里没有运行中的事件循环，新建一个跑完即弃
            return asyncio.run(处理_内容(*参数, **关键字参数))
    else:
        def 同步调用(*参数: Any, **关键字参数: Any) -> Any:
            return 处理_内容(*参数, **关键字参数)

    @wraps(处理_内容)
    async def 异步包装(*参数: Any, **关键字参数: Any) -> Any:
        循环 = asyncio.get_running_loop()
        # 透传 contextvars，保证日志/请求上下文等信息在子线程里可见
        上下文 = contextvars.copy_context()
        任务 = 循环.run_in_executor(
            线程池,
            lambda: 上下文.run(同步调用, *参数, **关键字参数),
        )
        return await 任务

    return 异步包装
