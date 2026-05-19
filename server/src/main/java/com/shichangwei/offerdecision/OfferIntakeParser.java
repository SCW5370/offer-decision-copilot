package com.shichangwei.offerdecision;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class OfferIntakeParser {

  private static final Pattern COMPENSATION_PATTERN =
      Pattern.compile("(\\d{1,3}\\s*[kK万Ww])(\\s*[xX×*]\\s*\\d{1,2})?");

  private static final List<String> STACK_TERMS =
      List.of(
          "Java",
          "Spring",
          "MySQL",
          "Postgres",
          "PostgreSQL",
          "Redis",
          "Kafka",
          "TypeScript",
          "Python",
          "Go",
          "LangGraph",
          "Flink",
          "Elasticsearch",
          "Docker",
          "Kubernetes");

  public IntakeParseResponse parse(String rawText) {
    String normalized = rawText == null ? "" : rawText.trim();
    List<String> warnings = new ArrayList<>();
    List<String> extractedSignals = new ArrayList<>();

    String company = extractByLabel(normalized, "公司", "企业", "单位");
    if (blank(company)) {
      company = guessCompany(normalized);
    } else {
      extractedSignals.add("识别到公司字段");
    }

    String role = extractByLabel(normalized, "岗位", "职位", "Role", "Job Title");
    if (blank(role)) {
      role = guessRole(normalized);
    } else {
      extractedSignals.add("识别到岗位字段");
    }

    String city = firstNonBlank(extractByLabel(normalized, "城市", "地点", "Base", "Location"), guessCity(normalized));
    if (!blank(city)) {
      extractedSignals.add("识别到城市或工作地点");
    }

    String compensation = extractCompensation(normalized);
    if (!blank(compensation)) {
      extractedSignals.add("识别到薪资结构");
    }

    String stage = inferStage(normalized);
    String workMode = inferWorkMode(normalized);
    String managerSupport = inferManagerSupport(normalized);
    String executionStyle = inferExecutionStyle(normalized);
    String domain = firstNonBlank(extractByLabel(normalized, "业务", "方向", "Domain"), summarizeDomain(normalized));
    String stack = inferStack(normalized);
    String jdSignals = firstNonBlank(extractByLabel(normalized, "职责", "工作内容", "JD", "岗位描述"), summarizeSignals(normalized));
    String notes = summarizeNotes(normalized, domain, jdSignals);

    int confidenceScore = 35;
    confidenceScore += !blank(company) ? 12 : 0;
    confidenceScore += !blank(role) ? 12 : 0;
    confidenceScore += !blank(compensation) ? 8 : 0;
    confidenceScore += !blank(stack) ? 10 : 0;
    confidenceScore += !blank(jdSignals) ? 10 : 0;
    confidenceScore += !"medium".equals(managerSupport) ? 4 : 0;
    confidenceScore += !"balanced".equals(executionStyle) ? 4 : 0;

    if (blank(company)) {
      warnings.add("没有稳定识别出公司名，建议你手动确认。");
    }
    if (blank(role)) {
      warnings.add("没有稳定识别出岗位名称，建议你手动补全。");
    }
    if (blank(stack)) {
      warnings.add("没有提取到足够明显的技术栈，后续分析会更依赖手工输入。");
    }
    if (blank(jdSignals)) {
      warnings.add("岗位职责摘要较弱，建议贴上更完整的 JD 或面试信息。");
    }

    ParsedOfferDraft draft =
        new ParsedOfferDraft(
            fallback(company, "待确认公司"),
            fallback(role, "待确认岗位"),
            fallback(city, "待确认"),
            fallback(compensation, "待确认"),
            stage,
            fallback(domain, "待补充业务方向"),
            workMode,
            fallback(stack, "待补充技术栈"),
            managerSupport,
            executionStyle,
            fallback(jdSignals, "待补充岗位职责与信号"),
            notes,
            normalized,
            "intake-parser");

    return new IntakeParseResponse(
        draft, Math.min(95, confidenceScore), warnings, extractedSignals);
  }

  private String extractByLabel(String text, String... labels) {
    for (String label : labels) {
      Pattern pattern =
          Pattern.compile("(?im)^\\s*" + Pattern.quote(label) + "\\s*[:：]\\s*(.+)$");
      Matcher matcher = pattern.matcher(text);
      if (matcher.find()) {
        return cleanLine(matcher.group(1));
      }
    }
    return "";
  }

  private String extractCompensation(String text) {
    Matcher matcher = COMPENSATION_PATTERN.matcher(text);
    if (matcher.find()) {
      return matcher.group().replaceAll("\\s+", "");
    }
    return "";
  }

  private String guessCompany(String text) {
    Matcher matcher = Pattern.compile("(?m)^\\s*([A-Z][A-Za-z0-9 .&-]{2,}|[\\u4e00-\\u9fa5]{2,20}(科技|智能|网络|软件|信息|数据|平台|公司))\\s*$").matcher(text);
    if (matcher.find()) {
      return cleanLine(matcher.group(1));
    }
    return "";
  }

  private String guessRole(String text) {
    Matcher matcher = Pattern.compile("(?m)^\\s*(.+?(工程师|开发|研发|架构师|产品经理|算法|后端|前端|平台))\\s*$").matcher(text);
    if (matcher.find()) {
      return cleanLine(matcher.group(1));
    }
    return "";
  }

  private String guessCity(String text) {
    for (String city : List.of("上海", "北京", "深圳", "杭州", "广州", "成都", "南京", "苏州", "武汉")) {
      if (text.contains(city)) {
        return city;
      }
    }
    return "";
  }

  private String inferStage(String text) {
    String lower = safeLower(text);
    if (containsAny(lower, "上市", "大厂", "成熟业务", "大型平台", "头部")) {
      return "big-tech";
    }
    if (containsAny(lower, "b轮", "c轮", "增长", "快速扩张", "scale-up")) {
      return "growth";
    }
    return "startup";
  }

  private String inferWorkMode(String text) {
    String lower = safeLower(text);
    if (containsAny(lower, "远程", "remote")) {
      return "remote";
    }
    if (containsAny(lower, "混合", "hybrid", "每周到岗")) {
      return "hybrid";
    }
    return "onsite";
  }

  private String inferManagerSupport(String text) {
    String lower = safeLower(text);
    if (containsAny(lower, "导师", "一对一", "带教", "培养", "mentor")) {
      return "high";
    }
    if (containsAny(lower, "自驱", "独立推进", "owner", "高自治")) {
      return "low";
    }
    return "medium";
  }

  private String inferExecutionStyle(String text) {
    String lower = safeLower(text);
    if (containsAny(lower, "规范", "成熟流程", "review", "稳定性", "流程完善", "标准化")) {
      return "structured";
    }
    if (containsAny(lower, "快速试错", "从 0 到 1", "模糊", "混乱", "救火")) {
      return "chaotic";
    }
    return "balanced";
  }

  private String summarizeDomain(String text) {
    for (String keyword :
        List.of("Agent", "AI", "模型", "平台", "SaaS", "风控", "搜索", "推荐", "广告", "数据平台", "企业服务")) {
      if (text.contains(keyword)) {
        return keyword + " 相关方向";
      }
    }
    return "";
  }

  private String inferStack(String text) {
    Set<String> found = new LinkedHashSet<>();
    String lower = safeLower(text);
    for (String term : STACK_TERMS) {
      if (lower.contains(term.toLowerCase(Locale.ROOT))) {
        found.add(term);
      }
    }
    return String.join(", ", found);
  }

  private String summarizeSignals(String text) {
    String[] lines = text.split("\\R");
    List<String> candidates = new ArrayList<>();
    for (String line : lines) {
      String cleaned = cleanLine(line);
      if (cleaned.length() < 8) {
        continue;
      }
      if (containsAny(cleaned, "负责", "参与", "建设", "优化", "平台", "稳定性", "评测", "编排")) {
        candidates.add(cleaned);
      }
      if (candidates.size() >= 2) {
        break;
      }
    }
    return String.join("；", candidates);
  }

  private String summarizeNotes(String rawText, String domain, String signals) {
    List<String> notes = new ArrayList<>();
    if (!blank(domain)) {
      notes.add("业务方向：" + domain);
    }
    if (!blank(signals)) {
      notes.add("职责摘要：" + clip(signals, 120));
    }
    if (notes.isEmpty()) {
      return clip(rawText.replaceAll("\\s+", " "), 140);
    }
    return String.join(" | ", notes);
  }

  private boolean containsAny(String text, String... needles) {
    for (String needle : needles) {
      if (safeLower(text).contains(safeLower(needle))) {
        return true;
      }
    }
    return false;
  }

  private String cleanLine(String value) {
    return value == null ? "" : value.trim().replaceAll("\\s+", " ");
  }

  private String clip(String value, int max) {
    if (blank(value) || value.length() <= max) {
      return cleanLine(value);
    }
    return cleanLine(value.substring(0, max)) + "...";
  }

  private String fallback(String value, String fallback) {
    return blank(value) ? fallback : cleanLine(value);
  }

  private String firstNonBlank(String first, String second) {
    return blank(first) ? second : first;
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private String safeLower(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }
}
