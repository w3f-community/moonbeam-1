package io.emeraldpay.moonbeam.processing

import io.emeraldpay.moonbeam.state.*
import io.libp2p.core.multiformats.Protocol
import org.apache.commons.codec.binary.Hex
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.function.Function

@Service
class FullProcessor(): Function<PeerDetails, ProcessedPeerDetails> {

    companion object {
        private val log = LoggerFactory.getLogger(FullProcessor::class.java)
    }

    private val agentParser = AgentParser()

    override fun apply(peer: PeerDetails): ProcessedPeerDetails {
        val result = ProcessedPeerDetails(peer.address)

        result.peerId = peer.peerId?.toString()
        //if peerid wasn't set by some reason try to extract from address
        if (result.peerId == null) {
            result.peerId = peer.address.getStringComponent(Protocol.P2P)
                    ?: peer.address.getStringComponent(Protocol.IPFS)
        }

        peer.agent?.let { agent ->
            result.agent = agentParser.parse(agent)
        }

        result.host = ProcessedPeerDetails.Host.from(peer)

        result.connection = ConnectionDetails(
                if (peer.incoming) { ConnectionDetails.ConnectionType.IN } else { ConnectionDetails.ConnectionType.OUT },
                peer.connectedAt,
                peer.disconnectedAt ?: Instant.now()
        )

        result.blockchain = peer.status?.let {
            Blockchain(
                    it.height,
                    Hex.encodeHexString(it.bestHash),
                    Hex.encodeHexString(it.genesis)
            )
        }

        result.protocols = peer.protocols?.let {
            Protocols(it)
        }

        return result
    }

}