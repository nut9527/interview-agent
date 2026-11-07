package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeDetails;

import java.text.Normalizer;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class ResumeImportContact {
    private enum Field { PHONE, EMAIL, LOCATION }

    private static final Pattern SEPARATOR = Pattern.compile("[|;\\t]");
    private static final Pattern PLACEHOLDER = Pattern.compile("(?i)x{2,}|[_*]{2,}|待补充|待填写|请填写");
    private static final Pattern PHONE = Pattern.compile("[+0-9() -]{6,40}");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9_+%'-]+(?:\\.[A-Za-z0-9_+%'-]+)*@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}");
    private static final Pattern LOCATION = Pattern.compile("[\\p{L}\\p{M}0-9][\\p{L}\\p{M}0-9 ,.'’·/()-]{0,119}");

    private final EnumMap<Field, String> values = new EnumMap<>(Field.class);

    ResumeDetails.Contact result() {
        return values.isEmpty() ? null : new ResumeDetails.Contact(values.get(Field.PHONE),
                values.get(Field.EMAIL), values.get(Field.LOCATION), List.of());
    }

    /** 返回 true 表示整行已归入联系方式或仅含模板占位符；其他文本还需保留供核对。 */
    boolean consume(String line) {
        boolean recognized = false;
        boolean complete = true;
        for (String part : SEPARATOR.split(Normalizer.normalize(line, Normalizer.Form.NFKC))) {
            if (part.isBlank()) continue;
            int colon = part.indexOf(':');
            Field field = colon < 0 ? null : switch (part.substring(0, colon).strip().toLowerCase(Locale.ROOT)) {
                case "电话", "联系电话", "手机", "手机号", "手机号码", "phone", "mobile", "tel", "telephone" -> Field.PHONE;
                case "邮箱", "联系邮箱", "电子邮箱", "电子邮件", "email", "e-mail" -> Field.EMAIL;
                case "城市", "所在城市", "现居地", "居住地", "city", "location" -> Field.LOCATION;
                default -> null;
            };
            if (field == null) {
                complete = false;
                continue;
            }
            recognized = true;
            String value = part.substring(colon + 1).strip();
            if (value.isEmpty() || PLACEHOLDER.matcher(value).matches()) continue;
            boolean valid = switch (field) {
                case PHONE -> PHONE.matcher(value).matches() && value.chars().filter(Character::isDigit).count() >= 6;
                case EMAIL -> value.length() <= 254 && value.indexOf('@') <= 64 && EMAIL.matcher(value).matches();
                case LOCATION -> value.length() <= 120 && LOCATION.matcher(value).matches();
            };
            if (valid) {
                String previous = values.putIfAbsent(field, value);
                if (previous != null && !previous.equals(value)) complete = false;
            } else {
                complete = false;
            }
        }
        return complete && recognized;
    }
}
