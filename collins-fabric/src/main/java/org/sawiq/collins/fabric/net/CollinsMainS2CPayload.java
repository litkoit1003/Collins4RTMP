package org.sawiq.collins.fabric.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record CollinsMainS2CPayload(byte[] data) implements CustomPayload {

    public static final CustomPayload.Id<CollinsMainS2CPayload> ID =
            new CustomPayload.Id<>(Identifier.of("collins", "main"));

    // ВАЖНО: не используем readByteArray()/writeByteArray() из-за лимитов, читаем "весь остаток"
    public static final PacketCodec<RegistryByteBuf, CollinsMainS2CPayload> CODEC = new PacketCodec<>() {
        @Override
        public CollinsMainS2CPayload decode(RegistryByteBuf buf) {
            int readable = buf.readableBytes();
            byte[] bytes = new byte[readable];
            buf.readBytes(bytes);
            return new CollinsMainS2CPayload(bytes);
        }

        @Override
        public void encode(RegistryByteBuf buf, CollinsMainS2CPayload payload) {
            buf.writeBytes(payload.data());
        }
    };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
