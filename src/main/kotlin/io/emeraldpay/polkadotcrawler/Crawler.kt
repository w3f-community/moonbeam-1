package io.emeraldpay.polkadotcrawler

import com.google.protobuf.ByteString
import identify.pb.IdentifyOuterClass
import io.emeraldpay.polkadotcrawler.crawler.CrawlerClient
import io.emeraldpay.polkadotcrawler.discover.Discovered
import io.emeraldpay.polkadotcrawler.discover.NoRecentChecks
import io.emeraldpay.polkadotcrawler.discover.PublicPeersOnly
import io.emeraldpay.polkadotcrawler.proto.Dht
import io.emeraldpay.polkadotcrawler.state.PeerDetails
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multiformats.Multiaddr
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.function.Consumer

@Service
class Crawler(
        @Autowired private val discovered: Discovered,
        @Autowired private val noRecentChecks: NoRecentChecks
): Runnable {

    companion object {
        private val log = LoggerFactory.getLogger(Crawler::class.java)
    }

    private val keys = generateKeyPair(KEY_TYPE.ED25519)

    private val agent = IdentifyOuterClass.Identify.newBuilder()
            .setAgentVersion("substrate-bot/0.1.0")
            .setProtocolVersion("/substrate/1.0")
//            .addProtocols("/substrate/ksmcc3/5")
//            .addProtocols("/substrate/sup/5")
            .addProtocols("/ipfs/ping/1.0.0")
            .addProtocols("/ipfs/id/1.0.0")
            .addProtocols("/ipfs/kad/1.0.0")
            .addListenAddrs(
                    ByteString.copyFrom(
                            Multiaddr(
                                    Multiaddr.fromString("/ip4/127.0.0.1/tcp/0"),
                                    PeerId.fromPubKey(keys.second)
                            ).getBytes()
                    )
            )
            .build()

    private val publicPeersOnly = PublicPeersOnly()

    override fun run() {
        Flux.from(discovered.listen())
                .subscribeOn(Schedulers.newSingle("crawler"))
                .filter(noRecentChecks)
                .flatMap {
                    connect(it)
                }
                .onErrorContinue { t, u ->
                    log.warn("Failed to connect", t)
                }
                .subscribe {
                    it.dump()
                }
    }

    fun connect(address: Multiaddr): Mono<PeerDetails> {
        try {
            val crawler = CrawlerClient(address, agent, keys)
            val result = crawler.connect()
                    .take(Duration.ofSeconds(60))
                    .doFinally {
                        crawler.disconnect()
                    }
                    .reduce(PeerDetails(address)) { details, it ->
                        log.debug("Received ${it.dataType} from $address")

                        when (it.dataType) {

                            CrawlerClient.DataType.DHT_NODES -> {
                                val dht = it.cast(Dht.Message::class.java)
                                details.add(dht.data)

                                dht.data.closerPeersList.flatMap {
                                    it.addrsList
                                }.mapNotNull {
                                    try {
                                        Multiaddr(it.toByteArray())
                                    } catch (e: java.lang.IllegalArgumentException) {
                                        log.debug("Invalid address")
                                        null
                                    }
                                }.filter {
                                    publicPeersOnly.test(it)
                                }.forEach {
                                    discovered.submit(it)
                                }
                            }

                            CrawlerClient.DataType.IDENTIFY -> {
                                val id = it.cast(IdentifyOuterClass.Identify::class.java)
                                details.add(id.data)
                            }
                        }

                        details
                    }
            return result
        } catch (e: Exception) {
            log.error("Failed to setup crawler connection", e)
            throw e
        }

    }
}