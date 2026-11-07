package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeDetails;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 可明确归类的履历使用编辑器的结构化字段，其余条目保留原文供核对。 */
record ResumeImportDetails(ResumeDetails details, List<String> educationText, List<String> projectText) {
    private static final String MONTH = "(?:19|20)\\d{2}\\h*[./年-]\\h*(?:1[0-2]|0?[1-9])月?";
    private static final String RANGE = "(" + MONTH + "\\h*(?:[-–—~～至]\\h*(?:" + MONTH + "|至今|现在)|至今))";
    private static final Pattern TRAILING_PERIOD = Pattern.compile("^([^:：]{1,120}?)\\h+" + RANGE + "$");
    private static final Pattern LEADING_PERIOD = Pattern.compile("^" + RANGE + "\\h+([^:：]{1,120})$");
    private static final Pattern NAMED_PROJECT = Pattern.compile("^(?:项目名称|Project name)\\h*[:：]\\h*(.{1,120})$", Pattern.CASE_INSENSITIVE);
    private static final String COLUMN = "[|｜\\h]++";
    private static final Pattern EDUCATION = Pattern.compile("^(.{1,150}?(?:大学|学院|学校))" + COLUMN
            + "([^|｜]{1,160}?)" + COLUMN + "(本科|专科|大专|硕士(?:研究生)?|博士(?:研究生)?|学士)" + COLUMN
            + "(" + MONTH + ")\\h*(?:[-–—~～至]\\h*(" + MONTH + "|至今)|(至今))$");
    private static final Pattern GRADUATION = Pattern.compile("(?:预计)?毕业(?:时间|年月)?\\h*[:：]?\\h*(" + MONTH + ")(?!\\d)"
            + "|(" + MONTH + ")\\h*(?:预计)?毕业");
    private static final Pattern YEAR_MONTH = Pattern.compile("((?:19|20)\\d{2})\\h*[./年-]\\h*(\\d{1,2})月?");
    private static final Pattern STACK_SEPARATOR = Pattern.compile("[、，,;；|｜/]|\\h{2,}|\\t");
    private static final Set<String> STACK_CATEGORIES = Set.of("agent", "前端", "后端", "数据库", "中间件", "工具");

    static ResumeImportDetails extract(ResumeDetails.Contact contact, List<String> education, List<String> projectLines) {
        List<ResumeDetails.Education> schools = new ArrayList<>();
        List<String> educationText = new ArrayList<>();
        String graduation = null;
        for (String line : education) {
            Matcher date = GRADUATION.matcher(line);
            if (date.find()) {
                String month = normalizeMonth(date.group(1) == null ? date.group(2) : date.group(1));
                if (graduation == null || month.compareTo(graduation) > 0) graduation = month;
            }
            Matcher row = EDUCATION.matcher(line);
            if (row.matches() && schools.size() < 10) {
                var entry = new ResumeDetails.Education(row.group(1).strip(), row.group(2).strip(), row.group(3),
                        normalizeMonth(row.group(4)), row.group(5) == null ? "至今" : normalizeMonth(row.group(5)));
                if (entry.isPeriodValid()) {
                    schools.add(entry);
                    continue;
                }
            }
            educationText.add(line);
        }

        List<ResumeDetails.Project> projects = new ArrayList<>();
        List<String> projectText = new ArrayList<>();
        for (int i = 0; i < projectLines.size();) {
            ProjectTitle title = projectTitle(projectLines.get(i));
            if (title == null || projects.size() >= 20) {
                projectText.add(projectLines.get(i++));
                continue;
            }
            int end = i + 1;
            while (end < projectLines.size() && projectTitle(projectLines.get(end)) == null) end++;
            var project = readProject(title, projectLines.subList(i + 1, end));
            if (project == null) projectText.addAll(projectLines.subList(i, end));
            else projects.add(project);
            i = end;
        }
        var details = new ResumeDetails(graduation, null, projects, contact, schools);
        return new ResumeImportDetails(details, educationText, projectText);
    }

    private static String normalizeMonth(String raw) {
        Matcher date = YEAR_MONTH.matcher(raw);
        if (!date.matches()) return raw; // 至今
        return String.format(Locale.ROOT, "%s-%02d", date.group(1), Integer.parseInt(date.group(2)));
    }

    private record ProjectTitle(String name, String period) {}

    private static ProjectTitle projectTitle(String line) {
        Matcher named = NAMED_PROJECT.matcher(line);
        boolean hasLabel = named.matches();
        String value = hasLabel ? named.group(1).strip() : line.strip();
        Matcher trailing = TRAILING_PERIOD.matcher(value);
        if (trailing.matches()) return new ProjectTitle(trailing.group(1).strip(), trailing.group(2));
        Matcher leading = LEADING_PERIOD.matcher(value);
        if (leading.matches()) return new ProjectTitle(leading.group(2).strip(), leading.group(1));
        return hasLabel && !value.isEmpty() ? new ProjectTitle(value, null) : null;
    }

    private static ResumeDetails.Project readProject(ProjectTitle title, List<String> body) {
        List<String> contributions = new ArrayList<>();
        var stack = new LinkedHashSet<String>();
        String role = null;
        String background = null;
        boolean afterStack = false;
        for (String line : body) {
            String[] field = line.split("[:：]", 2);
            String label = field.length == 2 ? field[0].strip().toLowerCase(Locale.ROOT) : "";
            String value = field.length == 2 ? field[1].strip() : "";
            boolean stackLabel = label.equals("技术栈") || label.equals("tech stack");
            if (stackLabel || afterStack && STACK_CATEGORIES.contains(label)) {
                List<String> terms = STACK_SEPARATOR.splitAsStream(value)
                        .map(String::strip).filter(term -> !term.isEmpty()).toList();
                if (!terms.isEmpty() && (stackLabel || terms.size() > 1 && !value.matches(".*[。！？].*"))) {
                    stack.addAll(terms);
                    afterStack = true;
                    continue;
                }
            }
            afterStack = false;
            if (background == null && !value.isEmpty() && (label.equals("项目简介") || label.equals("项目背景") || label.equals("项目描述"))) {
                background = value;
            } else if (role == null && !value.isEmpty() && (label.equals("角色") || label.equals("本人角色") || label.equals("项目角色"))) {
                role = value;
            } else {
                contributions.add(line);
            }
        }
        // 超出编辑器上限时保留整段原文，以免部分导入后丢失后续贡献。
        if (contributions.size() > 30 || stack.size() > 30 || stack.stream().anyMatch(tag -> tag.length() > 80)
                || role != null && role.length() > 120) return null;
        return new ResumeDetails.Project(title.name(), role, title.period(), background, contributions, List.copyOf(stack));
    }
}
