package com.wgh.rag.scheduler.singleflight;

import com.wgh.rag.config.redis.RedisCommandPort;
import com.wgh.rag.config.redis.RedisDistributedLock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleFlightGuardTest {

    private static final Duration TTL = Duration.ofSeconds(60);

    @Test
    void shouldFailClosedWhenRedisDisabled() {
        FakePort port = new FakePort();
        port.enabled = false;
        SingleFlightGuard guard = new SingleFlightGuard(new RedisDistributedLock(port));

        assertThatThrownBy(() -> guard.tryAcquire("job:CONNECTOR_SYNC", TTL))
                .isInstanceOf(SingleFlightUnavailableException.class)
                .hasMessageContaining("拒绝降级为进程内单飞");
    }

    @Test
    void shouldReturnNullWhenLockHeldElsewhere() {
        FakePort port = new FakePort();
        port.acquireResult = 0L;
        SingleFlightGuard guard = new SingleFlightGuard(new RedisDistributedLock(port));

        assertThat(guard.tryAcquire("job:CONNECTOR_SYNC", TTL)).isNull();
    }

    @Test
    void shouldAcquireRenewAndRelease() {
        FakePort port = new FakePort();
        SingleFlightGuard guard = new SingleFlightGuard(new RedisDistributedLock(port));

        SingleFlightGuard.Handle handle = guard.tryAcquire("job:CONNECTOR_SYNC", TTL);

        assertThat(handle).isNotNull();
        assertThat(handle.renew()).isTrue();
        handle.close();
        assertThat(port.releaseCalls).isEqualTo(1);
    }

    @Test
    void shouldReportLostLeaseWhenRenewFails() {
        FakePort port = new FakePort();
        SingleFlightGuard guard = new SingleFlightGuard(new RedisDistributedLock(port));
        SingleFlightGuard.Handle handle = guard.tryAcquire("job:CONNECTOR_SYNC", TTL);
        port.renewResult = 0L;

        assertThat(handle.renew()).isFalse();
    }

    /** 按脚本内容模拟 SET NX / PEXPIRE / DEL 三种命令。 */
    private static final class FakePort implements RedisCommandPort {

        private boolean enabled = true;

        private long acquireResult = 1L;

        private long renewResult = 1L;

        private int releaseCalls;

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public Long executeScript(String script, List<String> keys, List<String> args) {
            if (script.contains("'set'")) {
                return acquireResult;
            }
            if (script.contains("pexpire")) {
                return renewResult;
            }
            if (script.contains("'del'")) {
                releaseCalls++;
                return 1L;
            }
            return 0L;
        }

        @Override
        public String get(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException();
        }
    }
}
