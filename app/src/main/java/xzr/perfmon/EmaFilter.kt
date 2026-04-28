package xzr.perfmon

/**
 * 指數移動平均 (EMA) 工具類
 * 用於平滑硬體監控數據（如 CPU, GPU, APU 負載）
 * * @param alpha 平滑係數，範圍 0.0 ~ 1.0。
 * 值越大越敏感（反應快但跳動大），值越小越平滑（反應慢但數據穩）。
 * 建議值：0.2 ~ 0.3
 */
class EmaFilter(private val alpha: Float = 0.2f) {
    private var currentValue: Int = -1 // 初始值設為 -1 代表尚未初始化

    /**
     * 輸入原始數據，回傳平滑後的數據
     */
    fun update(input: Int): Int {
        if (currentValue == -1) {
            // 第一次輸入時，直接當作初始值
            currentValue = input
        } else {
            // EMA 公式: EMA = α * 新值 + (1 - α) * 舊值
            currentValue = (alpha * input + (1 - alpha) * currentValue).toInt()
        }
        return currentValue
    }

    /**
     * 重置過濾器
     */
    fun reset() {
        currentValue = -1
    }

    /**
     * 獲取當前平滑後的整數值 (方便 UI 顯示)
     */
    val currentInt: Int
        get() = currentValue
}