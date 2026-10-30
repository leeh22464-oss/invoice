package com.acme.invoice.ocr;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class ChineseAmount {

    private ChineseAmount() {
    }

    static BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.replace("人民币", "")
                .replace("整", "")
                .replace("正", "")
                .replace("元", "圆")
                .replace(",", "")
                .replace("，", "")
                .replace("￥", "")
                .replace("¥", "")
                .trim();
        if (text.isEmpty() || !containsChineseNumeral(text)) {
            return null;
        }
        int yuan = text.indexOf('圆');
        String integer = yuan >= 0 ? text.substring(0, yuan) : stripFraction(text);
        String fraction = yuan >= 0 ? text.substring(yuan + 1) : fractionPart(text);
        if (integer.isEmpty() && fraction.isEmpty()) {
            return null;
        }
        long whole = integer.isEmpty() ? 0 : section(integer);
        int jiao = digitBefore(fraction, '角');
        int fen = digitBefore(fraction, '分');
        return BigDecimal.valueOf(whole)
                .add(BigDecimal.valueOf(jiao).movePointLeft(1))
                .add(BigDecimal.valueOf(fen).movePointLeft(2))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean containsChineseNumeral(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (digit(text.charAt(index)) >= 0 || unit(text.charAt(index)) > 0) {
                return true;
            }
        }
        return false;
    }

    private static String stripFraction(String text) {
        int jiao = text.indexOf('角');
        int fen = text.indexOf('分');
        int cut = text.length();
        if (jiao >= 0) {
            cut = Math.min(cut, Math.max(0, jiao - 1));
        }
        if (fen >= 0) {
            cut = Math.min(cut, Math.max(0, fen - 1));
        }
        return text.substring(0, cut);
    }

    private static String fractionPart(String text) {
        int jiao = text.indexOf('角');
        int fen = text.indexOf('分');
        int start = -1;
        if (jiao >= 0) {
            start = jiao;
        }
        if (fen >= 0 && (start < 0 || fen < start)) {
            start = fen;
        }
        return start < 0 ? "" : text.substring(Math.max(0, start - 1));
    }

    private static int digitBefore(String text, char mark) {
        int at = text.indexOf(mark);
        if (at <= 0) {
            return 0;
        }
        int value = digit(text.charAt(at - 1));
        return Math.max(value, 0);
    }

    private static long section(String text) {
        long total = 0;
        long current = 0;
        long number = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            int digit = digit(ch);
            if (digit >= 0) {
                number = digit;
                continue;
            }
            int unit = unit(ch);
            if (unit == 10 || unit == 100 || unit == 1000) {
                current += (number == 0 ? 1 : number) * unit;
                number = 0;
            } else if (unit == 10_000 || unit == 100_000_000) {
                current += number;
                if (current == 0) {
                    current = 1;
                }
                total += current * unit;
                current = 0;
                number = 0;
            }
        }
        return total + current + number;
    }

    private static int digit(char ch) {
        return switch (ch) {
            case '零', '〇', '○' -> 0;
            case '壹', '一' -> 1;
            case '贰', '貳', '二', '两' -> 2;
            case '叁', '三' -> 3;
            case '肆', '四' -> 4;
            case '伍', '五' -> 5;
            case '陆', '六' -> 6;
            case '柒', '七' -> 7;
            case '捌', '八' -> 8;
            case '玖', '九' -> 9;
            default -> -1;
        };
    }

    private static int unit(char ch) {
        return switch (ch) {
            case '拾', '十' -> 10;
            case '佰', '百' -> 100;
            case '仟', '千' -> 1000;
            case '万' -> 10_000;
            case '亿' -> 100_000_000;
            default -> 0;
        };
    }
}
