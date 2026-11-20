package cn.edu.whu.zhizhida.ai.api;

public record ChatInput(String system, String user, double temperature, int maxTokens) {
}
