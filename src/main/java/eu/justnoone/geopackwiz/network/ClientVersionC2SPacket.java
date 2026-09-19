package eu.justnoone.geopackwiz.network;

import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public class ClientVersionC2SPacket implements FabricPacket {

    public static final ResourceLocation TYPE = new ResourceLocation("zbx_rpu", "client_version");

    public final String clientVersion;

    public ClientVersionC2SPacket(String serverLockKey) {
        this.clientVersion = serverLockKey;
    }

    public ClientVersionC2SPacket(FriendlyByteBuf buf) {
        this.clientVersion = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(clientVersion);
    }

    @Override
    public PacketType<ClientVersionC2SPacket> getType() {
        return PacketType.create(TYPE, ClientVersionC2SPacket::new);
    }
}
