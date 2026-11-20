package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.common.StructuredOutput;
import cn.edu.whu.zhizhida.common.StructuredOutput.RejectionReason;
import cn.edu.whu.zhizhida.resume.api.ResumeDetails;
import cn.edu.whu.zhizhida.resume.api.ResumeImportResult;
import cn.edu.whu.zhizhida.resume.api.ResumeImportResult.Warning;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile.ResumeProfileRequest;
import tools.jackson.databind.JsonNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 模型只负责从文档摘录和分类；模板、用户指定岗位、校验及缺失项由应用决定。 */
final class ResumeAiImportOutput {
    private static final Pattern MONTH = Pattern.compile("(?<![0-9])((?:19|20)[0-9]{2})\\h*[年./-]\\h*(0?[1-9]|1[0-2])月?(?![0-9])");
    private static final Pattern DATE_RANGE = Pattern.compile("(?<![0-9])((?:19|20)[0-9]{2}-(?:0[1-9]|1[0-2]))\\h*(?:[-至~]\\h*((?:19|20)[0-9]{2}-(?:0[1-9]|1[0-2])|至今)|(至今))(?![0-9])");
    private static final Pattern SPACING = Pattern.compile("[\\s\\p{Z}\\u00ad\\u200b]+");
    // 数字中的逗号和冒号、英文句点、斜杠、加号及井号具有实际含义。
    private static final Pattern LAYOUT = Pattern.compile("[|、。•▪▣]|(?<![0-9])[,;:]|[,;:](?![0-9])");
    private static final Pattern PLACEHOLDER = Pattern.compile("(?i)x{2,}|[_＊*＿—-]{2,}|待补充(?:姓名|目标岗位)?|未提供|未知|不详|无|n/a");
    private final String original;
    private final String document;
    private final String source;
    private final String sourceWithoutLayout;
    private final List<Warning> warnings = new ArrayList<>();

    private ResumeAiImportOutput(String original) {
        this.document = original;
        this.original = Normalizer.normalize(original, Normalizer.Form.NFKC);
        source = comparable(this.original);
        sourceWithoutLayout = LAYOUT.matcher(source).replaceAll("");
    }

    static ResumeImportResult parse(JsonNode payload, String source, ResumeProfileRequest local, String targetRoleOverride) {
        if (payload == null || !payload.isObject()) throw StructuredOutput.invalid("$");
        return new ResumeAiImportOutput(source).read(payload, local, targetRoleOverride);
    }

    private ResumeImportResult read(JsonNode root, ResumeProfileRequest local, String override) {
        String owner = excerpt(root, "ownerName", 80);
        String role = override == null || override.isBlank() ? null : override.strip();
        if (role != null && (role.length() > 120 || PLACEHOLDER.matcher(role).matches())) {
            warn("targetRole", role.length() > 120 ? RejectionReason.LIMIT_EXCEEDED : RejectionReason.INVALID_VALUE);
            role = null;
        }
        boolean roleOverridden = role != null;
        if (role == null) role = excerpt(root, "targetRole", 120);
        String summary = excerpt(root, "summary", 4000);
        List<String> skills = excerpts(root, "skills", 30, 80);
        var educationText = new LinkedHashSet<>(excerpts(root, "educationExperiences", 100, 3000));
        List<String> projectText = excerpts(root, "projectExperiences", 100, 3000);
        List<String> internships = excerpts(root, "internshipExperiences", 100, 3000);
        List<String> certificates = excerpts(root, "certificates", 30, 80);
        JsonNode detail = object(root, "details", "details");
        List<ResumeDetails.Education> education = new ArrayList<>();
        for (Entry item : objects(detail, "education", "details.education", 10)) {
            JsonNode entry = item.value();
            String path = item.path();
            String school = excerpt(entry, "school", 160, path + ".school");
            String major = excerpt(entry, "major", 160, path + ".major");
            String degree = excerpt(entry, "degree", 80, path + ".degree");
            String start = excerpt(entry, "startDate", 120, path + ".startDate");
            String end = excerpt(entry, "endDate", 120, path + ".endDate");
            var row = new ResumeDetails.Education(school, major, degree, start, end);
            if (school != null && major != null && degree != null && validMonth(start)
                    && (validMonth(end) || "至今".equals(end)) && row.isPeriodValid()) {
                education.add(row);
            } else {
                warn(path, Stream.of(school, major, degree, start, end).anyMatch(value -> value == null)
                        ? RejectionReason.MISSING_FIELD : RejectionReason.INVALID_VALUE);
                String raw = educationExcerpt(school, major, degree, start, end);
                if (raw != null) educationText.add(raw);
            }
        }
        List<ResumeDetails.Project> projects = new ArrayList<>();
        for (Entry item : objects(detail, "projects", "details.projects", 20)) {
            JsonNode entry = item.value();
            String path = item.path();
            String name = excerpt(entry, "name", 120, path + ".name");
            if (name == null) {
                missing(path + ".name");
                continue;
            }
            projects.add(new ResumeDetails.Project(name, excerpt(entry, "role", 120, path + ".role"),
                    excerpt(entry, "period", 120, path + ".period"), excerpt(entry, "background", 2000, path + ".background"),
                    excerpts(entry, "contributions", 30, 3000, path + ".contributions"),
                    excerpts(entry, "techStack", 30, 80, path + ".techStack")));
        }

        String graduation = excerpt(detail, "graduationDate", 120, "details.graduationDate");
        String availability = excerpt(detail, "availability", 200, "details.availability");
        ResumeDetails.Contact contact = readContact(object(detail, "contact", "details.contact"));
        boolean body = summary != null || !skills.isEmpty() || !educationText.isEmpty() || !education.isEmpty()
                || !projectText.isEmpty() || !projects.isEmpty() || !internships.isEmpty() || !certificates.isEmpty();
        if (!body && owner == null && contact == null && availability == null && graduation == null
                && (role == null || roleOverridden)) {
            if (!warnings.isEmpty()) {
                Warning first = warnings.getFirst();
                String field = first.field().replaceAll("\\[[0-9]+\\]", "");
                throw StructuredOutput.invalid(field.substring(field.lastIndexOf('.') + 1), first.code());
            }
            throw StructuredOutput.invalid("$", RejectionReason.EMPTY_VALUE);
        }
        if (!body && hasLocalSections(local)) warn("$", RejectionReason.MISSING_FIELD);
        if (owner == null) missing("ownerName");
        if (role == null) missing("targetRole");

        ResumeDetails localDetails = local.details() == null ? ResumeDetails.empty() : local.details();
        var details = new ResumeDetails(first(graduation, localValue("details.graduationDate", localDetails.graduationDate())), availability, projects,
                mergeContact(contact, localDetails.contact()), education);
        var draft = new ResumeProfileRequest(first(owner, ""), first(role, ""),
                summary, skills, List.copyOf(educationText), projectText, internships, certificates, "classic", null, details);
        return new ResumeImportResult(draft, List.copyOf(warnings), ResumeImportResult.RecognitionMode.AI);
    }

    private ResumeDetails.Contact readContact(JsonNode node) {
        if (node == null) return null;
        var checked = new ResumeImportContact();
        for (String key : List.of("phone", "email", "location")) {
            int limit = switch (key) { case "phone" -> 40; case "email" -> 254; default -> 120; };
            String value = excerpt(node, key, limit, "details.contact." + key);
            if (value != null && (Normalizer.normalize(value, Normalizer.Form.NFKC).matches("(?s).*[|;\\t].*")
                    || !checked.consume(key + ":" + value))) warn("details.contact." + key, RejectionReason.INVALID_VALUE);
        }
        List<ResumeDetails.Link> links = new ArrayList<>();
        for (Entry item : objects(node, "links", "details.contact.links", 8)) {
            JsonNode entry = item.value();
            String url = excerpt(entry, "url", 1000, item.path() + ".url");
            if (url == null) {
                missing(item.path() + ".url");
                continue;
            }
            if (!url.matches("(?i)https?://[^\\s/]+(?:/[^\\s]*)?")) {
                warn(item.path() + ".url", RejectionReason.INVALID_VALUE);
                continue;
            }
            String label = checked(item.path() + ".label", () -> optional(entry, "label", 80));
            if (label != null && !source.contains(comparable(label))) {
                warnings.add(new Warning(item.path() + ".label", RejectionReason.SEMANTIC_MISMATCH,
                        "链接名称无法与原文核对，链接已保留并显示为“个人链接”。"));
                label = null;
            }
            links.add(new ResumeDetails.Link(first(label, "个人链接"), url));
        }
        var values = checked.result();
        if (values == null && links.isEmpty()) return null;
        return values == null ? new ResumeDetails.Contact(null, null, null, links)
                : new ResumeDetails.Contact(values.phone(), values.email(), values.location(), links);
    }

    private ResumeDetails.Contact mergeContact(ResumeDetails.Contact model, ResumeDetails.Contact local) {
        if (local == null) return model;
        String phone = first(model == null ? null : model.phone(), localValue("details.contact.phone", local.phone()));
        String email = first(model == null ? null : model.email(), localValue("details.contact.email", local.email()));
        String location = first(model == null ? null : model.location(), localValue("details.contact.location", local.location()));
        List<ResumeDetails.Link> links = model == null ? List.of() : model.links();
        return phone == null && email == null && location == null && links.isEmpty() ? null
                : new ResumeDetails.Contact(phone, email, location, links);
    }

    private String localValue(String path, String value) {
        return warnings.stream().anyMatch(warning -> path.equals(warning.field()) || path.startsWith(warning.field() + "."))
                ? null : value;
    }

    private String educationExcerpt(String school, String major, String degree, String start, String end) {
        if (school == null) return null;
        var markers = Stream.of(school, major, degree, start, end).filter(value -> value != null).map(ResumeAiImportOutput::comparable).toList();
        if (markers.size() < 2) return null;
        var lines = document.lines().filter(line -> line.length() <= 3000
                && markers.stream().allMatch(marker -> comparable(line).contains(marker))).toList();
        return lines.size() == 1 ? lines.getFirst().strip() : null;
    }

    private static boolean hasLocalSections(ResumeProfileRequest local) {
        return Stream.of(local.educationExperiences(), local.projectExperiences(), local.internshipExperiences(),
                        local.skills(), local.certificates()).anyMatch(list -> list != null && !list.isEmpty())
                || local.details() != null && (!local.details().projects().isEmpty() || !local.details().education().isEmpty());
    }

    private static boolean validMonth(String value) {
        return value != null && value.matches("[0-9]{4}-(?:0[1-9]|1[0-2])");
    }

    private static String first(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String comparable(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String dates = MONTH.matcher(normalized.replace('–', '-').replace('—', '-')).replaceAll(match ->
                match.group(1) + "-" + (match.group(2).length() == 1 ? "0" : "") + match.group(2));
        String ranges = DATE_RANGE.matcher(dates).replaceAll(match ->
                match.group(1) + "-" + (match.group(2) == null ? match.group(3) : match.group(2)));
        return SPACING.matcher(ranges).replaceAll("");
    }

    private JsonNode object(JsonNode node, String field, String path) {
        if (node == null || !node.hasNonNull(field)) return null;
        JsonNode value = node.get(field);
        if (!value.isObject()) {
            warn(path, RejectionReason.TYPE_MISMATCH);
            return null;
        }
        return value;
    }

    private record Entry(JsonNode value, String path) {}

    private List<Entry> objects(JsonNode node, String field, String path, int limit) {
        JsonNode values = array(node, field, path, limit);
        if (values == null) return List.of();
        List<Entry> rows = new ArrayList<>();
        for (int index = 0; index < Math.min(values.size(), limit); index++) {
            JsonNode value = values.get(index);
            String itemPath = path + "[" + index + "]";
            if (!value.isObject()) warn(itemPath, RejectionReason.TYPE_MISMATCH);
            else rows.add(new Entry(value, itemPath));
        }
        return rows;
    }

    private JsonNode array(JsonNode node, String field, String path, int limit) {
        if (node == null || !node.hasNonNull(field)) return null;
        JsonNode values = node.get(field);
        if (!values.isArray()) {
            warn(path, RejectionReason.TYPE_MISMATCH);
            return null;
        }
        if (values.size() > limit) warn(path, RejectionReason.LIMIT_EXCEEDED);
        return values;
    }

    private <T> T checked(String path, Supplier<T> reader) {
        try {
            return reader.get();
        } catch (StructuredOutput.FieldValidationException ex) {
            warn(path, ex.reason());
            return null;
        }
    }

    private void missing(String path) {
        if (warnings.stream().noneMatch(warning -> warning.field().equals(path))) warn(path, RejectionReason.MISSING_FIELD);
    }

    private void warn(String path, RejectionReason reason) {
        String message = switch (reason) {
            case MISSING_FIELD -> "信息不完整，请对照原文补充。";
            case TYPE_MISMATCH, INVALID_JSON -> "识别结果格式不正确，已跳过，请核对补充。";
            case LIMIT_EXCEEDED -> "识别内容超过字段上限，已跳过超出部分，请核对补充。";
            case SEMANTIC_MISMATCH -> "识别内容无法与原文核对，已清除，请核对补充。";
            default -> "识别内容不符合字段格式，已清除，请核对补充。";
        };
        warnings.add(new Warning(path, reason, message));
    }

    private static String optional(JsonNode node, String field, int limit) {
        if (node == null || !node.hasNonNull(field)) return null;
        JsonNode value = node.get(field);
        if (value.isString() && value.stringValue().isBlank()) return null;
        String text = StructuredOutput.text(node, field, limit);
        return PLACEHOLDER.matcher(text).matches() ? null : text;
    }

    private String excerpt(JsonNode node, String field, int limit) {
        return excerpt(node, field, limit, field);
    }

    private String excerpt(JsonNode node, String field, int limit, String path) {
        return checked(path, () -> {
            String value = optional(node, field, limit);
            if (value != null) verifyExcerpt(field, value);
            return value;
        });
    }

    private List<String> excerpts(JsonNode node, String field, int count, int limit) {
        return excerpts(node, field, count, limit, field);
    }

    private List<String> excerpts(JsonNode node, String field, int count, int limit, String path) {
        JsonNode values = array(node, field, path, count);
        if (values == null) return List.of();
        var result = new LinkedHashSet<String>();
        for (int index = 0; index < Math.min(values.size(), count); index++) {
            JsonNode value = values.get(index);
            String text = checked(path + "[" + index + "]", () -> {
                if (!value.isString()) throw StructuredOutput.invalid(field, RejectionReason.TYPE_MISMATCH);
                String item = value.stringValue().strip();
                if (item.isEmpty()) throw StructuredOutput.invalid(field, RejectionReason.EMPTY_VALUE);
                if (item.length() > limit) throw StructuredOutput.invalid(field, RejectionReason.LIMIT_EXCEEDED);
                verifyExcerpt(field, item);
                return item;
            });
            if (text != null) result.add(text);
        }
        return List.copyOf(result);
    }

    private void verifyExcerpt(String field, String value) {
        String expected = comparable(value);
        boolean present = switch (field) {
            // 地址与大小写敏感路径直接核对原文，避免宽松标点匹配改变其含义。
            case "url" -> Pattern.compile(Pattern.quote(value) + "(?=$|[\\s\\p{Z}<>\\p{IsHan}、。|]|[.,;)\\]](?:\\s|\\p{IsHan}|$))")
                    .matcher(original).find();
            case "email" -> Pattern.compile("(?<![\\w.+%'-])" + Pattern.quote(value) + "(?![\\w@-]|\\.[\\w])")
                    .matcher(original).find();
            case "techStack", "skills" -> matchesSkill(value, expected);
            case "phone" -> Pattern.compile("(?<![0-9])" + Pattern.quote(expected) + "(?![0-9])").matcher(source).find();
            case "ownerName", "location", "startDate", "endDate", "graduationDate" -> source.contains(expected);
            default -> matchesText(expected);
        };
        if (expected.isEmpty() || !present) throw StructuredOutput.invalid(field, StructuredOutput.RejectionReason.SEMANTIC_MISMATCH);
    }

    private boolean matchesSkill(String value, String expected) {
        if (!value.matches("[A-Za-z0-9+#./_-]+(?:\\h+[A-Za-z0-9+#./_-]+)*")) {
            return value.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                    ? matchesText(expected) : source.contains(expected);
        }
        String[] words = value.strip().split("\\h+");
        String term = String.join("[\\s\\p{Z}]*", Stream.of(words).map(Pattern::quote).toList());
        return Pattern.compile("(?<![a-z0-9#+_])" + term + "(?![a-z0-9#+_])", Pattern.CASE_INSENSITIVE)
                .matcher(original).find();
    }

    private boolean matchesText(String expected) {
        String layoutFree = LAYOUT.matcher(expected).replaceAll("").replaceFirst("\\.$", "");
        return source.contains(expected) || !layoutFree.isEmpty() && sourceWithoutLayout.contains(layoutFree);
    }
}
