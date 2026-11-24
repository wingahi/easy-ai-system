package com.wgh.rag.scheduler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 调度中心配置（{@code wgh.scheduler}）。
 *
 * <p>全部字段都有代码默认值：{@code application*.yml} 未配置时按默认值运行。
 * 三条车道各自独立线程池与排队上限，后台车道与交互式车道互不争用线程。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wgh.scheduler")
public class SchedulerProperties {

    /** 调度中心总开关；关闭后不创建任何车道线程池与调度循环。 */
    private boolean enabled = true;

    /** 实例标识；为空时取 {@code HOSTNAME + pid}，用于租约持有者与单飞锁归属。 */
    private String instanceId;

    /** 作业租约 TTL；持有者需在到期前续租，否则作业可被其它实例回收。 */
    private Duration leaseTtl = Duration.ofSeconds(60);

    /** 租约续约与取消轮询间隔；必须显著小于 {@link #leaseTtl}。 */
    private Duration heartbeatInterval = Duration.ofSeconds(10);

    /** 后台车道领取作业的轮询间隔。 */
    private Duration claimInterval = Duration.ofSeconds(2);

    /** 租约过期回收扫描间隔。 */
    private Duration reclaimInterval = Duration.ofSeconds(30);

    /** 实例心跳写入间隔；多副本存活判定依赖该心跳。 */
    private Duration instanceHeartbeatInterval = Duration.ofSeconds(10);

    /** 实例存活阈值：心跳超过该时长判定失联，其持有作业被提前回收（不等租约自然到期）。 */
    private Duration instanceLivenessTtl = Duration.ofSeconds(45);

    /** 失联实例扫描间隔；建议不大于存活阈值的一半。 */
    private Duration instanceSweepInterval = Duration.ofSeconds(10);

    /** 实例注册行保留时长（超过后清理历史行，只影响管理端历史展示）。 */
    private Duration instanceRetention = Duration.ofDays(7);

    /** 排队超时扫描间隔。 */
    private Duration queueSweepInterval = Duration.ofSeconds(15);

    /** 单次作业默认超时；请求未指定 {@code timeout} 时使用。 */
    private Duration defaultTimeout = Duration.ofMinutes(30);

    /** 后台作业排队超时；超过后退回 FAILED(JOB_QUEUE_TIMEOUT)，不再重试。 */
    private Duration queueTimeout = Duration.ofMinutes(5);

    /** 作业失败后的重试退避基数（按第几次尝试线性递增）。 */
    private Duration retryBackoff = Duration.ofSeconds(30);

    /** 作业默认最大尝试次数（含首次）。 */
    private int maxAttempts = 3;

    /** 终态事件在内存事件总线中的保留时长，供 attach 重放。 */
    private Duration eventRetention = Duration.ofMinutes(5);

    /** 事件表保留期；超过后由调度层低频清理，避免无限增长。 */
    private Duration eventTableRetention = Duration.ofDays(7);

    /** 单租户后台并发上限（策略表无覆盖时的默认值，见 {@link #defaultPolicy}）。 */
    private int tenantMaxConcurrent = 2;

    /** 匿名/默认租户标识；未携带租户上下文的请求统一落在该桶。 */
    private String anonymousTenantId = "default";

    /** 租户策略缓存刷新间隔；请求路径只读本地缓存，不查库。 */
    private Duration policyRefreshInterval = Duration.ofSeconds(30);

    /** 集群运行槽与作业表实际在跑数的对账间隔。 */
    private Duration slotReconcileInterval = Duration.ofMinutes(1);

    /** 默认租户策略；策略表无对应 (tenant_id, lane) 行时使用。 */
    private DefaultPolicy defaultPolicy = new DefaultPolicy();

    /** 交互式车道。 */
    private Lane interactive = Lane.of(4, 16, 100, "sched-interactive-");

    /** 后台车道。 */
    private Lane background = Lane.of(2, 8, 500, "sched-background-");

    /** 维护车道。 */
    private Lane maintenance = Lane.of(1, 2, 50, "sched-maintenance-");

    /** 单飞（Redis 分布式锁）参数。 */
    private SingleFlight singleFlight = new SingleFlight();

    /** 作业状态与业务状态对账参数。 */
    private Reconcile reconcile = new Reconcile();

    /** 对账参数。 */
    @Data
    public static class Reconcile {

        /** 是否启用周期对账。 */
        private boolean enabled = true;

        /** 对账间隔。 */
        private Duration interval = Duration.ofMinutes(10);

        /** 只对账最近该时长内创建/更新的作业，避免全表扫描。 */
        private Duration lookback = Duration.ofHours(24);

        /** PENDING 作业超过该时长仍与业务终态不一致时才自动取消，避免与刚提交的作业竞争。 */
        private Duration pendingGrace = Duration.ofMinutes(5);

        /** 单类型单轮最多检查条数。 */
        private int batchSize = 200;
    }

    /** 车道参数。 */
    @Data
    public static class Lane {

        /** 核心线程数。 */
        private int coreSize;

        /** 最大并发（线程池最大线程数，也是该车道的在途上限）。 */
        private int maxSize;

        /** 有界队列容量：交互式为线程池队列，后台/维护为作业表 PENDING 上限。 */
        private int queueCapacity;

        /** 线程名前缀。 */
        private String threadNamePrefix;

        static Lane of(int coreSize, int maxSize, int queueCapacity, String threadNamePrefix) {
            Lane lane = new Lane();
            lane.coreSize = coreSize;
            lane.maxSize = maxSize;
            lane.queueCapacity = queueCapacity;
            lane.threadNamePrefix = threadNamePrefix;
            return lane;
        }
    }

    /** 单飞锁参数。 */
    @Data
    public static class SingleFlight {

        /** 锁 TTL；执行期间的续约由调度层按心跳周期完成。 */
        private Duration lockTtl = Duration.ofSeconds(120);

        /** 未抢到锁或 Redis 不可用时，作业回到 PENDING 的重试延迟。 */
        private Duration retryDelay = Duration.ofSeconds(10);
    }

    /** 默认租户策略。 */
    @Data
    public static class DefaultPolicy {

        /** 交互式默认策略。 */
        private LanePolicy interactive = LanePolicy.of(2, 20, 10, 0, "REJECT", 300);

        /** 后台默认策略。 */
        private LanePolicy background = LanePolicy.of(2, 200, 10, 0, "QUEUE", 300);
    }

    /** 单车道租户策略默认值。 */
    @Data
    public static class LanePolicy {

        /** 运行中并发上限（不含排队）。 */
        private int maxConcurrent;

        /** 等待中排队上限。 */
        private int maxQueue;

        /** 公平调度权重（1-100），只影响排队顺序，不做抢占。 */
        private int weight;

        /** 作业基础优先级加成。 */
        private int priorityBoost;

        /** 拒绝策略：REJECT 立即拒绝 / QUEUE 允许排队。 */
        private String rejectPolicy;

        /** 排队超时秒数；超时转失败。 */
        private int queueTimeoutSeconds;

        static LanePolicy of(int maxConcurrent, int maxQueue, int weight, int priorityBoost,
                             String rejectPolicy, int queueTimeoutSeconds) {
            LanePolicy policy = new LanePolicy();
            policy.maxConcurrent = maxConcurrent;
            policy.maxQueue = maxQueue;
            policy.weight = weight;
            policy.priorityBoost = priorityBoost;
            policy.rejectPolicy = rejectPolicy;
            policy.queueTimeoutSeconds = queueTimeoutSeconds;
            return policy;
        }
    }
}
