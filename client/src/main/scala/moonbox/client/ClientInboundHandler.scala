/*-
 * <<
 * Moonbox
 * ==
 * Copyright (C) 2016 - 2018 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */

package moonbox.client

import java.util.concurrent.ConcurrentHashMap

import io.netty.channel.{Channel, ChannelHandlerContext, ChannelInboundHandlerAdapter, ChannelPromise}
import io.netty.util.ReferenceCountUtil
import moonbox.common.MbLogging
import moonbox.common.message._

class ClientInboundHandler(promises: ConcurrentHashMap[Long, ChannelPromise], responses: ConcurrentHashMap[Long, JdbcOutboundMessage], callbacks: ConcurrentHashMap[Long, JdbcOutboundMessage => Any]) extends ChannelInboundHandlerAdapter with MbLogging {

  private var ctx: ChannelHandlerContext = _

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    this.ctx = ctx
    super.channelActive(ctx)
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
    try {
      if (!promises.isEmpty) {
        handleMessageWithPromise(msg)
      }
      if (!callbacks.isEmpty) {
        handleMessageWithCallback(msg)
      }
    } finally {
      ReferenceCountUtil.release(msg)
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    cause.printStackTrace()
    ctx.close
  }

  private def handleMessageWithCallback(response: Any): Any = {
    response match {
      case echo: EchoOutbound => callback(echo.messageId, echo)
      case login: JdbcLoginOutbound => callback(login.messageId, login)
      case logout: JdbcLogoutOutbound => callback(logout.messageId, logout)
      case query: JdbcQueryOutbound => callback(query.messageId, query)
      case dataFetch: DataFetchOutbound => callback(dataFetch.dataFetchState.messageId, dataFetch)
      case cancel: JdbcCancelOutbound => callback(cancel.messageId, cancel)
      case _ => throw new Exception("Unsupported message")
    }
  }

  /**
    * get and run the callback function
    *
    * @param key      callback function key
    * @param response message response from the server
    */
  private def callback(key: Long, response: JdbcOutboundMessage): Any = {
    val callback = callbacks.get(key)
    if (callback != null) {
      callbacks.remove(key)
      callback(response)
    } else null
  }

  private def handleMessageWithPromise(msg: Any) = {
    msg match {
      case resp: JdbcOutboundMessage =>
        val id = msg match {
          case echo: EchoOutbound => echo.messageId
          case login: JdbcLoginOutbound => login.messageId
          case logout: JdbcLogoutOutbound => logout.messageId
          case query: JdbcQueryOutbound => query.messageId
          case cancel: JdbcCancelOutbound => cancel.messageId
          case dataFetch: DataFetchOutbound => dataFetch.dataFetchState.messageId
        }
        if (promises.containsKey(id)) {
            responses.put(id, resp)
            promises.get(id).setSuccess()
        }
      case _ => throw new Exception("Unsupported message")
    }
  }
}
