package ph.anevaino.common.ratelimit

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private const val MAX_TRACKED_KEYS = 10_000

class TokenBucketRateLimiter(
    private val capacity: Int,
    private val refillPeriod: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Decision(val allowed: Boolean, val retryAfterSeconds: Long)

    private class Bucket(var tokens: Double, var lastRefillMillis: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryConsume(key: String): Decision {
        if (buckets.size > MAX_TRACKED_KEYS) {
            evictIdleBuckets()
        }
        val now = clock.millis()
        val bucket = buckets.computeIfAbsent(key) { Bucket(capacity.toDouble(), now) }
        synchronized(bucket) {
            val elapsedMillis = now - bucket.lastRefillMillis
            val refilled = elapsedMillis.toDouble() * capacity / refillPeriod.toMillis()
            bucket.tokens = minOf(capacity.toDouble(), bucket.tokens + refilled)
            bucket.lastRefillMillis = now
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                return Decision(true, 0)
            }
            val millisUntilToken = ((1.0 - bucket.tokens) * refillPeriod.toMillis() / capacity).toLong()
            return Decision(false, maxOf(1L, (millisUntilToken + 999) / 1000))
        }
    }

    fun evictIdleBuckets() {
        val cutoff = clock.millis() - refillPeriod.toMillis() * 2
        buckets.entries.removeIf { it.value.lastRefillMillis < cutoff }
    }
}