#!/usr/bin/env python3
"""
文本预处理脚本
在发送给AI之前对输入文本进行清洗和标准化
"""

import re
import sys

def preprocess(text):
    # 去除多余空白
    text = re.sub(r'\s+', ' ', text).strip()
    # 标准化引号
    text = text.replace('"', '"').replace('"', '"')
    text = text.replace(''', "'").replace(''', "'")
    # 去除HTML标签
    text = re.sub(r'<[^>]+>', '', text)
    return text

if __name__ == '__main__':
    input_text = sys.stdin.read()
    print(preprocess(input_text))
