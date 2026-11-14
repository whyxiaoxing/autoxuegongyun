from ._ocr_filter import set_confusion as _set_confusion_raw
from ._ocr_filter import tesseract_process as _tesseract_process_raw

__all__ = ["set_confusion", "tesseract_process"]


def tesseract_process(
    tsv: str | bytes,
    pattern: str,
    max_edit: int = 0,
    gap_ratio: float = 1.0,
    min_conf: float = 0.0,
) -> dict[str, list[int]]:
    """
    在 Tesseract TSV 输出中定位目标串。

    tsv        原始 TSV（bytes 走零拷贝；str 也可）
    pattern    目标串，最多 64 个字符
    max_edit   保留字段；混淆集模式下固定按精确 key 匹配
    gap_ratio  相邻字间隙 / 字高 的上限
    min_conf   最小置信度，接受 0..1 或 0..100

    返回 {原始OCR文字: [中心x, 中心y]}，匹配文字取 OCR 实际识别内容，
    而非目标串；相同文字重复出现时后者覆盖前者。
    """
    return _tesseract_process_raw(tsv, pattern, max_edit, gap_ratio, min_conf)


def set_confusion(target: str, variants: list[str]) -> None:
    """
    运行时追加 OCR 混淆对：搜索 target 时同时召回 variants 中的字符。

    target 与 variants 均须为单个字符；关系是有向的（target -> variants）。
    """
    _set_confusion_raw(target, variants)
