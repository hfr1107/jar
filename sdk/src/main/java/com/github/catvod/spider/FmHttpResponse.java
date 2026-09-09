package com.github.catvod.spider;

public final class FmHttpResponse {
    public final int status;
    public final String url;
    public final String text;
    public final String base64;
    public final String error;

    public FmHttpResponse(int status, String url, String text, String base64, String error) {
        this.status = status;
        this.url = url;
        this.text = text;
        this.base64 = base64;
        this.error = error;
    }

    public boolean ok() { return status >= 200 && status < 300; }
}
