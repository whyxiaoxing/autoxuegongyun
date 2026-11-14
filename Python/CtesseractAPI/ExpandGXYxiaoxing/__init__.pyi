def tesseract_process(
    tsv: str | bytes,
    pattern: str,
    max_edit: int = 0,
    gap_ratio: float = 1.0,
    min_conf: float = 0.0,
) -> dict[str, list[int]]: ...
def set_confusion(target: str, variants: list[str]) -> None: ...
