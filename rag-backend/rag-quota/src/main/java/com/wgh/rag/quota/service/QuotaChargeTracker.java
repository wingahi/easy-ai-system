package com.wgh.rag.quota.service;

import com.wgh.rag.common.metrics.RagMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 异步续跑（HITL 确认）的计费收尾器：把「扣费凭据」和「是否已下发有效产出」绑定在一起。
 *
 * <p>与对话链路的 {@code StreamBillingObserver} 语义一致：首个有效产出下发后结算；
 * 整轮结束仍无产出则立即退款，失败交给补偿作业兜底。同一凭据的结算/退款在数据库层幂等，
 * 本类只负责避免重复调用与收尾时机。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuotaChargeTracker {

    private final QuotaService quotaService;
    private final RagMetrics metrics;

    /** 绑定一次扣费；{@code charge} 可为 {@code null}（fail-open 未实际扣费）。 */
    public Handle track(QuotaService.QuotaCharge charge) {
        return new Handle(charge);
    }

    /** 一次续跑的计费句柄，线程安全。 */
    public final class Handle {

        private final QuotaService.QuotaCharge charge;
        private final AtomicBoolean outputDelivered = new AtomicBoolean(false);

        private Handle(QuotaService.QuotaCharge charge) {
            this.charge = charge;
        }

        /** 首个有效产出已下发：结算扣费（只生效一次）。 */
        public void markOutput() {
            if (charge == null) {
                return;
            }
            if (outputDelivered.compareAndSet(false, true)) {
                quotaService.markChargeSettled(charge);
            }
        }

        public boolean hasOutput() {
            return outputDelivered.get();
        }

        /**
         * 再次停在 HITL 的收尾：不算失败，不退款。
         *
         * <p>已产出则结算；未产出则保持 CHARGED，等下一次续跑复用同一条流水，
         * 避免「退—再扣」循环；若长时间无人续跑，由补偿作业的长保护期兜底退款。</p>
         */
        public void finishOnPause() {
            if (charge == null) {
                return;
            }
            if (outputDelivered.get()) {
                quotaService.markChargeSettled(charge);
            }
        }

        /**
         * 续跑收尾：有产出确保结算（幂等兜底）；无产出立即退款。
         * 可在执行线程的 finally 中调用，异常不应影响主流程。
         */
        public void finish() {
            if (charge == null) {
                return;
            }
            if (outputDelivered.get()) {
                quotaService.markChargeSettled(charge);
                return;
            }
            QuotaService.RefundResult result;
            try {
                result = quotaService.refundChat(charge);
            } catch (Exception e) {
                log.error("续跑无产出退款异常，等待补偿作业兜底: username={}, chargeId={}, reason={}",
                        charge.username(), charge.chargeId(), e.getMessage());
                return;
            }
            metrics.recordQuotaRefund(result == null ? "unknown" : result.name().toLowerCase(Locale.ROOT));
            if (result == QuotaService.RefundResult.FAILED) {
                log.error("续跑无产出退款失败，等待补偿作业兜底: username={}, chargeId={}",
                        charge.username(), charge.chargeId());
            } else {
                log.info("本次续跑未下发有效内容，按无产出不计费处理: username={}, chargeId={}, result={}",
                        charge.username(), charge.chargeId(), result);
            }
        }
    }
}
