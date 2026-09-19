package eu.justnoone.geopackwiz.network;

import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public class ServerLockS2CPacket implements FabricPacket {

    public static final ResourceLocation TYPE = new ResourceLocation("zbx_rpu", "server_lock");

    public final String serverLockKey;

    public ServerLockS2CPacket(String serverLockKey) {
        this.serverLockKey = serverLockKey;
    }

    public ServerLockS2CPacket(FriendlyByteBuf buf) {
        this.serverLockKey = buf.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(serverLockKey);
    }

    @Override
    public PacketType<ServerLockS2CPacket> getType() {
        return PacketType.create(TYPE, ServerLockS2CPacket::new);
    }
}
