/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package network.thunder.core.communication.nio.handler.mid;

import com.google.gson.Gson;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import network.thunder.core.communication.Message;
import network.thunder.core.communication.Type;
import network.thunder.core.communication.nio.P2PContext;
import network.thunder.core.communication.objects.p2p.DataObject;
import network.thunder.core.communication.objects.p2p.sync.PubkeyIPObject;
import network.thunder.core.database.DatabaseHandler;
import network.thunder.core.etc.Tools;
import network.thunder.core.mesh.Node;

import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.sql.SQLException;
import java.util.ArrayList;

/* This layer is for coordinating gossip messages we received.
 * Messages are sent using the Node.class.
 */
public class GossipHandler extends ChannelInboundHandlerAdapter {

    private Node node;
    private boolean isServer = false;

    private P2PContext context;

    public GossipHandler (boolean isServer, Node node, P2PContext context) {
        this.isServer = isServer;
        this.node = node;
        this.context = context;
    }

    @Override
    public void channelActive (final ChannelHandlerContext ctx) throws SQLException {
        System.out.println("CHANNEL ACTIVE GOSSIP");

        node.conn = context.dataSource.getConnection();

        try {
            if (!isServer) {
                //The newly connected node will broadcast it's existence to all other nodes once the connection has been established.
                sendOwnIPAddress(ctx);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public void sendOwnIPAddress (ChannelHandlerContext ctx) throws UnsupportedEncodingException, NoSuchAlgorithmException, NoSuchProviderException {
        PubkeyIPObject pubkeyIPObject = new PubkeyIPObject();
        pubkeyIPObject.pubkey = this.context.nodeKey.getPubKey();
        pubkeyIPObject.port = this.context.port;
        pubkeyIPObject.IP = "127.0.0.1";
        pubkeyIPObject.timestamp = Tools.currentTime();
        pubkeyIPObject.sign(context.nodeKey);

        sendIPAddress(ctx, pubkeyIPObject);
    }

    public void sendIPAddress (ChannelHandlerContext ctx, PubkeyIPObject pubkeyIPObject) throws UnsupportedEncodingException, NoSuchAlgorithmException, NoSuchProviderException {
        ctx.writeAndFlush(new Message(pubkeyIPObject, Type.GOSSIP_SEND_IP_OBJECT));
    }

    public void sendGetAddr (ChannelHandlerContext ctx) {
        ctx.writeAndFlush(new Message(null, Type.GOSSIP_GET_ADDR));
    }

    public void sendData (ChannelHandlerContext ctx, ArrayList<DataObject> dataList) {
        try {
            ctx.writeAndFlush(new Message(dataList, Type.GOSSIP_SEND)).sync();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void sendFailure (ChannelHandlerContext ctx) {
        ctx.writeAndFlush(new Message(null, Type.FAILURE));
    }

    @Override
    public void channelRead (ChannelHandlerContext ctx, Object msg) throws Exception {
        try {

            //Check authentication first before doing anything else with the messages.
            Message message = (Message) msg;

//			System.out.println(message.type);

            if (message.type >= 1200 && message.type <= 1299) {

                if (message.type == Type.GOSSIP_SEND_IP_OBJECT) {
                    //Other node sent us a new IP object. We check whether we know it already and if we don't, we send it to all other nodes...
                    PubkeyIPObject pubkeyIPObject = new Gson().fromJson(message.data, PubkeyIPObject.class);
                    pubkeyIPObject.verifySignature();

                    //Check if it is new to us
                    if (DatabaseHandler.newIPObject(node.conn, pubkeyIPObject)) {
                        //Object is new to us, broadcast to all other connected peers..
                        for (Node n : context.connectedNodes) {
                            sendIPAddress(n.getNettyContext(), pubkeyIPObject);
                        }
                    }
                }

                if(message.type == Type.GOSSIP_INV) {

                }

                if (message.type == Type.GOSSIP_SEND) {
                    //We get lots of different data here..
                    DataObject[] dataList = new Gson().fromJson(message.data, DataObject[].class);
                    for (DataObject o : dataList) {
                        if (o.type == DataObject.TYPE_IP_PUBKEY) {
                            PubkeyIPObject ipObject = o.getPubkeyIPObject();
                            ipObject.verify();
                            context.newIP(ipObject);
                        }
                    }
                }

            } else if (message.type == 0) {
                System.out.println("Got Failure:");
                System.out.println(message);
            } else {
                //Pass it further to the next handler
                ctx.fireChannelRead(msg);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @Override
    public void exceptionCaught (ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
