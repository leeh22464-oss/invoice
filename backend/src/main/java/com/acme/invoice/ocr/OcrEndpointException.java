package com.acme.invoice.ocr;

public class OcrEndpointException extends RuntimeException {

    static final String MESSAGE =
            "当前服务域名不是星河大模型接口。服务域名应填写 https://aistudio.baidu.com/llm/lmapi/v3，访问令牌从个人中心的访问令牌页复制，不要把令牌页网址填到服务域名。";

    public OcrEndpointException() {
        super(MESSAGE);
    }
}
