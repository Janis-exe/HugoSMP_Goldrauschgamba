package de.schiessbude.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;

/**
 * Optionales eigenes Paket vom Server: Kanal {@code schiessbude:reward}.
 * Inhalt: reiner UTF-8-Text "gewinn_id" oder "gewinn_id|Detail", z. B. "goldbarren|+1.250.000 $".
 * <p>
 * Beispiel für ein Bukkit/Paper-Plugin:
 * <pre>
 * player.sendPluginMessage(plugin, "schiessbude:reward", "goldticket".getBytes(StandardCharsets.UTF_8));
 * </pre>
 */
public record RewardPayload(String data) implements CustomPacketPayload {
	public static final Type<RewardPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("schiessbude", "reward"));
	/** Mehr braucht "gewinn_id|Detail" nie. Alles darüber wird ungelesen verworfen. */
	public static final int MAX_BYTES = 256;

	public static final StreamCodec<FriendlyByteBuf, RewardPayload> CODEC = StreamCodec.of(
			(buf, payload) -> buf.writeBytes(payload.data().getBytes(StandardCharsets.UTF_8)),
			buf -> {
				int total = buf.readableBytes();
				byte[] bytes = new byte[Math.min(total, MAX_BYTES)];
				buf.readBytes(bytes);
				buf.skipBytes(total - bytes.length);
				// zu große Pakete werden komplett ignoriert (leerer Inhalt = unbekannter Gewinn)
				return new RewardPayload(total > MAX_BYTES ? "" : new String(bytes, StandardCharsets.UTF_8));
			});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
