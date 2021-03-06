package io.emeraldpay.moonbeam.monitoring

import io.emeraldpay.moonbeam.state.PeerDetails
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.extra.processor.TopicProcessor
import java.time.Duration

/**
 * Central class for monitoring of the crawler
 */
@Repository
class Monitoring: Runnable {

    companion object {
        private val log = LoggerFactory.getLogger(Monitoring::class.java)
    }

    private val processed = TopicProcessor.create<PeerDetails>()

    fun onPeerProcessed(peer: PeerDetails) {
        processed.onNext(peer)
    }

    override fun run() {
        Flux.from(processed)
                .doOnNext {
                    PrometheusMetric.reportPeers(it.peers)
                    it.disconnectedAt?.let { dt ->
                        PrometheusMetric.reportConnectionTime(it.connectedAt, dt)
                    }
                }
                .window(Duration.ofSeconds(60))
                .flatMap { f ->
                    f.count()
                }
                .subscribe {
                    log.info("Found ${it} peers")
                }
    }

}