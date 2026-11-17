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

	public static final StreamCodec<FriendlyByteBuf, RewardPayload> CODEC = StreamCodec.of(
			(buf, payload) -> buf.writeBytes(payload.data().getBytes(StandardCharsets.UTF_8)),
			buf -> {
				byte[] bytes = new byte[buf.readableBytes()];
				buf.readBytes(bytes);
				return new RewardPayload(new String(bytes, StandardCharsets.UTF_8));
			});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
