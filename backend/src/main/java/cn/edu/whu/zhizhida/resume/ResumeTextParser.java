package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeProfileRequest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按常见中英文标题和“标签：内容”提取简历字段的启发式解析器。
 * 未识别内容沿当前段落归类，缺失项使用待补充值，结果需由用户核对后保存。
 */
@Component
public class ResumeTextParser {

    private static final Pattern LABEL_VALUE = Pattern.compile("^([^:：]{1,16})[:：]\\s*(.+)$");
    private static final int MAX_LINES = 400;

    public ResumeProfileRequest parse(String text, String fileName, String fallbackTargetRole) {
        List<String> lines = text.lines()
                .map(this::normalizeLine)
                .filter(line -> !line.isBlank())
                .limit(MAX_LINES)
                .toList();

        Map<Section, List<String>> sections = new EnumMap<>(Section.class);
        for (Section section : Section.values()) {
            sections.put(section, new ArrayList<>());
        }

        String ownerName = "";
        String targetRole = fallbackTargetRole == null ? "" : fallbackTargetRole.trim();
        Section current = Section.SUMMARY;
        for (String line : lines) {
            Matcher matcher = LABEL_VALUE.matcher(line);
            if (matcher.matches()) {
                Section labeled = sectionFor(matcher.group(1));
                if (labeled == Section.NAME) {
                    ownerName = matcher.group(2).trim();
                    continue;
                }
                if (labeled == Section.TARGET) {
                    if (targetRole.isBlank()) {
                        targetRole = matcher.group(2).trim();
                    }
                    continue;
                }
                if (labeled != null) {
                    current = labeled;
                    addLine(sections.get(current), matcher.group(2));
                    continue;
                }
            }

            Section heading = sectionFor(line);
            if (heading != null) {
                current = heading;
                continue;
            }
            addLine(sections.get(current), line);
        }

        if (ownerName.isBlank()) {
            ownerName = inferOwnerName(lines, fileName);
        }
        if (targetRole.isBlank()) {
            targetRole = "待补充目标岗位";
        }

        List<String> skills = splitSkills(sections.get(Section.SKILLS));
        return new ResumeProfileRequest(
                shorten(ownerName, 80),
                shorten(targetRole, 120),
                shorten(String.join("；", sections.get(Section.SUMMARY)), 4000),
                skills,
                copy(sections.get(Section.EDUCATION)),
                copy(sections.get(Section.PROJECTS)),
                copy(sections.get(Section.INTERNSHIPS)),
                splitSkills(sections.get(Section.CERTIFICATES)),
                "classic"
        );
    }

    private String normalizeLine(String line) {
        return line.replace('\u00a0', ' ')
                .replaceFirst("^[\\s•·●▪■◆◇★☆_-]+", "")
                .trim().replaceAll("\\s{2,}", " ");
    }

    private Section sectionFor(String raw) {
        String value = raw.toLowerCase(Locale.ROOT).replaceAll("[\\s:：()（）]", "");
        return switch (value) {
            case "姓名", "name", "个人姓名" -> Section.NAME;
            case "目标岗位", "求职意向", "应聘岗位", "targetrole", "position" -> Section.TARGET;
            case "个人简介", "个人总结", "自我评价", "专业概述", "summary", "profile" -> Section.SUMMARY;
            case "技能", "专业技能", "技能清单", "skills", "skill" -> Section.SKILLS;
            case "教育经历", "教育背景", "education" -> Section.EDUCATION;
            case "项目经历", "项目经验", "projects", "projectexperience" -> Section.PROJECTS;
            case "实习经历", "工作经历", "工作经验", "internship", "experience", "workexperience" -> Section.INTERNSHIPS;
            case "证书", "荣誉证书", "获奖证书", "certificates", "certificate" -> Section.CERTIFICATES;
            default -> null;
        };
    }

    private String inferOwnerName(List<String> lines, String fileName) {
        if (!lines.isEmpty()) {
            String first = lines.get(0);
            if (first.length() <= 20 && sectionFor(first) == null && !first.contains("简历")) {
                return first;
            }
        }
        String baseName = fileName == null ? "" : fileName.replaceFirst("(?i)\\.(pdf|docx?|dotx?)$", "").trim();
        return baseName.isBlank() ? "待补充姓名" : baseName;
    }

    private List<String> splitSkills(List<String> source) {
        Set<String> values = new LinkedHashSet<>();
        source.stream()
                .flatMap(line -> Pattern.compile("[,，、;；|/]").splitAsStream(line))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> shorten(value, 80))
                .limit(30)
                .forEach(values::add);
        return List.copyOf(values);
    }

    private void addLine(List<String> target, String value) {
        String normalized = shorten(normalizeLine(value), 500);
        if (!normalized.isBlank() && target.size() < 100) {
            target.add(normalized);
        }
    }

    private List<String> copy(List<String> source) {
        return List.copyOf(new LinkedHashSet<>(source));
    }

    private String shorten(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private enum Section {
        NAME, TARGET, SUMMARY, SKILLS, EDUCATION, PROJECTS, INTERNSHIPS, CERTIFICATES
    }
}
