package cn.edu.whu.zhizhida.ai.api;

/** 只读参考服务由本地配置固定，模型不能选择 URL 或身份。 */
public interface ReferenceToolClient {
    String fingerprint();
    String search(String query, int topK);
}
