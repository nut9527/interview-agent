package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeDetails;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile.ResumeProfileRequest;

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

/** 按中英文标题识别简历字段，保留待用户核对的原文。 */
@Component
public class ResumeTextParser {

    private static final Pattern LABEL_VALUE = Pattern.compile("^([^:：]{1,30})[:：]\\s*(.+)$");
    private static final String ROLE_TITLE =
            "[\\p{L}\\p{N}#+./-][\\p{L}\\p{N} #+./-]{0,54}(?:实习生?|工程师|开发|设计师|经理|专员|助理|engineer|developer|intern)";
    private static final Pattern ROLE = Pattern.compile(ROLE_TITLE, Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER_COLUMNS = Pattern.compile(
            "[\\t|｜]+|(?<=\\S) ++(?=(?:[\\p{IsHan}]{2,6}|name|target role|position|phone|mobile|tel(?:ephone)?|e-?mail|location|city)\\s*[:：])"
                    + "|(?<=\\S) {2,}+(?=" + ROLE_TITLE + "(?:$|[\\t|｜]))", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_NAME = Pattern.compile(
            "[\\p{IsHan}]{2,4}|[\\p{IsLatin}][\\p{IsLatin}'’-]*(?: [\\p{IsLatin}][\\p{IsLatin}'’-]*){0,3}");
    private static final Pattern TAG_SEPARATOR = Pattern.compile("[,，、;；|｜/]");
    private static final Pattern PROSE_TAG = Pattern.compile(
            "[。！？：:]|^(?:熟练|熟悉|掌握|使用|实现|实践|具备|了解|能够|参与|负责|擅长|精通|开展)");
    private static final int MAX_LINES = 400;

    public ResumeProfileRequest parse(String text, String fileName, String fallbackTargetRole) {
        List<String> lines = text.lines().map(this::normalizeLine)
                .filter(line -> !line.isBlank()).limit(MAX_LINES).toList();
        Map<Section, List<String>> sections = new EnumMap<>(Section.class);
        for (Section section : Section.values()) {
            sections.put(section, new ArrayList<>());
        }

        Section current = Section.HEADER;
        List<String> headerLines = new ArrayList<>();
        for (String line : lines) {
            Matcher label = LABEL_VALUE.matcher(line);
            boolean inline = label.matches();
            Section heading = sectionFor(inline ? label.group(1) : line);
            if (heading == Section.HEADER) {
                current = Section.HEADER;
                if (inline) {
                    headerLines.add(label.group(2));
                }
            } else if (current == Section.HEADER && (heading == null || isIdentity(heading))) {
                headerLines.add(line);
            } else if (heading != null) {
                if (!inline || !isIdentity(heading)) {
                    current = heading;
                }
                if (inline) {
                    addLine(sections.get(heading), label.group(2));
                }
            } else {
                addLine(sections.get(current), line);
                if (isIdentity(current)) {
                    current = Section.SUMMARY;
                }
            }
        }

        List<String> headerSummary = new ArrayList<>();
        ResumeDetails.Contact contact = readHeader(headerLines, sections, headerSummary);
        headerSummary.addAll(sections.get(Section.SUMMARY));
        String ownerName = sections.get(Section.NAME).stream().findFirst().orElseGet(() -> fileNameFallback(fileName));
        String targetRole = fallbackTargetRole == null || fallbackTargetRole.isBlank()
                ? sections.get(Section.TARGET).stream().findFirst().orElse("待补充目标岗位")
                : fallbackTargetRole.trim();
        var imported = ResumeImportDetails.extract(contact, sections.get(Section.EDUCATION), sections.get(Section.PROJECTS));
        return new ResumeProfileRequest(
                shorten(ownerName, 80), shorten(targetRole, 120), shorten(String.join("；", headerSummary), 4000),
                tags(sections.get(Section.SKILLS)), copy(imported.educationText()), copy(imported.projectText()),
                copy(sections.get(Section.INTERNSHIPS)), tags(sections.get(Section.CERTIFICATES)),
                "classic", null, imported.details()
        );
    }

    private ResumeDetails.Contact readHeader(List<String> lines, Map<Section, List<String>> sections, List<String> summary) {
        var contact = new ResumeImportContact();
        Section pending = null;
        String inferredName = "";
        String inferredRole = "";
        for (String line : lines) {
            // 表格单元格也可能将标签与值用制表符分开，如“姓名：\t李明”。
            String row = line.replaceAll("([:：])\\h+", "$1 ");
            for (String part : HEADER_COLUMNS.split(row)) {
                String cell = part.strip();
                if (cell.isEmpty()) {
                    continue;
                }
                Matcher label = LABEL_VALUE.matcher(cell);
                boolean inline = label.matches();
                Section heading = sectionFor(inline ? label.group(1) : cell);
                if (isIdentity(heading)) {
                    pending = heading;
                    if (!inline) {
                        continue;
                    }
                    cell = label.group(2).strip();
                } else if (contact.consume(cell) || isDocumentTitle(cell)) {
                    pending = null;
                    continue;
                }
                if (pending != null) {
                    if (pending == Section.NAME && cell.matches("[_＿—-]+")) {
                        cell = "待补充姓名";
                    }
                    sections.get(pending).add(cell);
                    pending = null;
                } else if (inferredRole.isEmpty() && ROLE.matcher(cell).matches()) {
                    inferredRole = cell;
                } else if (inferredName.isEmpty() && sections.get(Section.NAME).isEmpty() && summary.isEmpty()
                        && BARE_NAME.matcher(cell).matches()) {
                    inferredName = cell;
                } else {
                    addLine(summary, cell);
                }
            }
        }
        if (sections.get(Section.NAME).isEmpty() && !inferredName.isEmpty()) {
            sections.get(Section.NAME).add(inferredName);
        }
        if (sections.get(Section.TARGET).isEmpty() && !inferredRole.isEmpty()) {
            sections.get(Section.TARGET).add(inferredRole);
        }
        return contact.result();
    }

    private boolean isIdentity(Section section) {
        return section == Section.NAME || section == Section.TARGET;
    }

    private boolean isDocumentTitle(String value) {
        return value.matches("(?i)个人简历|求职简历|简历|resume|curriculum vitae|cv");
    }

    private String normalizeLine(String line) {
        return line.replace('\u00a0', ' ').strip()
                .replaceFirst("^[•·●▪■◆◇★☆▣]+\\s*|^[-_]\\s+", "").strip();
    }

    private Section sectionFor(String raw) {
        String value = raw.toLowerCase(Locale.ROOT).replaceAll("[\\s　:：【】()（）]", "");
        return switch (value) {
            case "姓名", "name", "个人姓名" -> Section.NAME;
            case "目标岗位", "求职意向", "应聘岗位", "意向岗位", "targetrole", "position" -> Section.TARGET;
            case "基本信息", "个人信息", "基本资料", "personalinformation" -> Section.HEADER;
            case "个人简介", "个人总结", "自我评价", "专业概述", "summary", "profile" -> Section.SUMMARY;
            case "技能", "专业技能", "技能清单", "技术技能", "技能特长", "skills", "skill", "technicalskills" -> Section.SKILLS;
            case "教育经历", "教育背景", "education" -> Section.EDUCATION;
            case "项目经历", "项目经验", "projects", "projectexperience" -> Section.PROJECTS;
            case "实习经历", "实习经验", "工作经历", "工作经验", "internship", "internships", "experience", "workexperience" -> Section.INTERNSHIPS;
            case "证书", "荣誉证书", "获奖证书", "荣誉奖项", "获奖经历", "荣誉与补充信息", "certificates", "certificate", "awards" -> Section.CERTIFICATES;
            default -> null;
        };
    }

    private String fileNameFallback(String fileName) {
        String base = fileName == null ? "" : fileName.replaceFirst("(?i)\\.(pdf|docx?|dotx?)$", "").trim();
        return base.isBlank() ? "待补充姓名" : base;
    }

    private List<String> tags(List<String> lines) {
        Set<String> values = new LinkedHashSet<>();
        for (String line : lines) {
            for (String part : tagParts(line)) {
                if (!part.isBlank()) {
                    values.add(shorten(part.strip(), 80));
                }
                if (values.size() == 30) {
                    return List.copyOf(values);
                }
            }
        }
        return List.copyOf(values);
    }

    private String[] tagParts(String line) {
        Matcher label = LABEL_VALUE.matcher(line);
        if (!label.matches()) {
            return TAG_SEPARATOR.split(line);
        }
        String[] items = TAG_SEPARATOR.split(label.group(2));
        // 只有冒号后明确列举技术名时才去掉分类名；保留含实践说明的整句。
        for (String item : items) {
            String value = item.strip();
            if (value.length() > 32 || PROSE_TAG.matcher(value).find()) {
                return new String[]{line};
            }
        }
        return items.length > 1 ? items : new String[]{line};
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
        HEADER, NAME, TARGET, SUMMARY, SKILLS, EDUCATION, PROJECTS, INTERNSHIPS, CERTIFICATES
    }
}
