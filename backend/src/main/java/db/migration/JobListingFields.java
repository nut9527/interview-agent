package db.migration;

import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.List;
import java.util.regex.Pattern;

/** Frozen V17 conversion, isolated from the retired public job module. */
record JobListingFields(LocalDate closingDate, boolean closed, Double salaryValue) {
    private static final Pattern DATE = Pattern.compile("^(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?.*$");
    private static final Pattern SALARY = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*k(?:\\s*[-–—~至]\\s*(\\d+(?:\\.\\d+)?)\\s*k)?", Pattern.CASE_INSENSITIVE);
    private static final List<String> CLOSED = List.of("招聘已结束", "招聘已经结束", "招聘已截止", "报名已截止", "网申已截止", "已停止招聘", "停止招聘", "暂停招聘", "不再招聘", "职位已下线", "岗位已下线");
    static JobListingFields of(String validThrough, String description, String salary) {
        LocalDate date = null;
        var match = DATE.matcher(validThrough == null ? "" : validThrough.trim());
        if (match.matches()) {
            try { date = LocalDate.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3))); }
            catch (DateTimeException ignored) { }
        }
        Double value = null;
        var amount = SALARY.matcher(salary == null ? "" : salary);
        if (amount.find()) {
            double lower = Double.parseDouble(amount.group(1));
            value = amount.group(2) == null ? lower : (lower + Double.parseDouble(amount.group(2))) / 2;
        }
        String text = description == null ? "" : description;
        return new JobListingFields(date, CLOSED.stream().anyMatch(text::contains), value);
    }
}
