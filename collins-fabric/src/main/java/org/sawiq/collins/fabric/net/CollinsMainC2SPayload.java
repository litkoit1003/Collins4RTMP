package org.sawiq.collins.fabric.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record CollinsMainC2SPayload(byte[] data) implements CustomPayload {

    public static final CustomPayload.Id<CollinsMainC2SPayload> ID =
            new CustomPayload.Id<>(Identifier.of("collins", "main"));

    public static final PacketCodec<RegistryByteBuf, CollinsMainC2SPayload> CODEC = new PacketCodec<>() {
        @Override
        public CollinsMainC2SPayload decode(RegistryByteBuf buf) {
            int readable = buf.readableBytes();
            byte[] bytes = new byte[readable];
            buf.readBytes(bytes);
            return new CollinsMainC2SPayload(bytes);
        }

        @Override
        public void encode(RegistryByteBuf buf, CollinsMainC2SPayload payload) {
            buf.writeBytes(payload.data());
        }
    };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
