package cn.edu.whu.zhizhida.interview.api;

/** Server-controlled question depth; never inferred from untrusted JD instructions. */
public enum InterviewDifficulty {
    BEGINNER("入门", "从基础概念与本人职责切入，一次询问一个清晰问题，适当提示思考方向，重点确认理解与基本实践。"),
    STANDARD("标准", "按真实岗位面试深度考察，追问个人行动、方案选择与验证依据，兼顾基础和实践。"),
    ADVANCED("挑战", "增加复杂约束、技术原理、备选方案、故障边界与反例迁移的考察深度，要求解释取舍和验证，保持尊重且一次只问一个核心问题。");

    private final String label;
    private final String guidance;
    InterviewDifficulty(String label, String guidance) { this.label = label; this.guidance = guidance; }
    public String label() { return label; }
    public String guidance() { return guidance; }
}
