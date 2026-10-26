package cn.edu.whu.zhizhida.common;

public record PageRequest(int page, int size) {
    public PageRequest {
        if (page < 1 || page > 1_000_000 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page 必须为 1–1000000，size 必须为 1–100");
        }
    }

    public long offset() { return (long) (page - 1) * size; }
    /** 仅将校验通过的数值拼接到 MyBatis 查询中。 */
    public String limitSql() { return "LIMIT " + size + " OFFSET " + offset(); }
}
