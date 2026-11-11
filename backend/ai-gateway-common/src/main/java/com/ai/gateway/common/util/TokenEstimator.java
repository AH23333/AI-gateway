package com.ai.gateway.common.util;

import com.ai.gateway.common.dto.ChatRequest;

import java.util.List;

/**
 * Token 估算器（v2 新增，选型书 5.1 / 1.md §6.1）
 *
 * 用途：
 * 1. 预扣估算（替代基线"固定预检 2000 token"）：est_total = est_input + est_output；
 * 2. 复杂度分级的"输入长度"特征；
 * 3. 预算过滤的成本预估。
 *
 * 估算口径（1.md §6.1 定稿）：
 * - 中文约 1 字 ≈ 1 token，英文约 4 字符 ≈ 1 token（CJK 判定走 Unicode 区段）；
 * - est_output = min(est_input × 0.5, 2048) + 128（OQ-02 关闭口径）。
 *
 * 为什么不用分词器精确计算？
 * 预扣是"防超扣的近似闸门"，结算以模型返回的实际用量为准（settle）；
 * 引入分词器会把上游 tokenizer 版本耦合进网关，得不偿失。
 */
public final class TokenEstimator {

    /** est_output 公式上限 */
    private static final long EST_OUTPUT_CAP = 2048L;

    /** est_output 公式底数 */
    private static final long EST_OUTPUT_BASE = 128L;

    private TokenEstimator() {
    }

    /** 估算一段文本的 token 数：CJK 每字 1 token，其余每 4 字符 1 token */
    public static long estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long cjk = 0;
        long others = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else {
                others++;
            }
        }
        return cjk + (others + 3) / 4;
    }

    /** 估算整个请求的输入 token 数（逐条消息累加 + 每条少量结构性开销） */
    public static long estimateInput(List<ChatRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (ChatRequest.Message msg : messages) {
            total += estimateText(msg.getContent()) + 4;
        }
        return total;
    }

    /** 输出 token 预估（OQ-02 关闭口径）：min(est_input × 0.5, 2048) + 128 */
    public static long estimateOutput(long estInput) {
        long half = estInput / 2;
        return Math.min(half, EST_OUTPUT_CAP) + EST_OUTPUT_BASE;
    }

    /** 预扣总额估算：est_input + est_output（结算按实际用量，预扣只需覆盖量级） */
    public static long estimateTotal(long estInput) {
        return estInput + estimateOutput(estInput);
    }

    /** CJK 字符判定（覆盖常用中日韩区段与扩展A区） */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // CJK 统一表意
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF)  // 兼容表意
                || (c >= 0x3000 && c <= 0x303F); // CJK 标点
    }
}
