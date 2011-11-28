/**
 * Copyright (C) 2009-2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.stomp.benchmark

import java.net._
import java.io._
import org.fusesource.hawtdispatch._
import java.nio.channels.{SelectionKey, SocketChannel}
import java.nio.ByteBuffer
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.fusesource.stompjms.client.callback._
import java.lang.Throwable
import org.fusesource.hawtbuf.Buffer._
import org.fusesource.stompjms.client.{StompFrame, Stomp}

/**
 * <p>
 * Simulates load on the a stomp broker using non blocking io.
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class NonBlockingScenario extends Scenario {

  import Scenario._

  def createProducer(i:Int) = {
    new ProducerClient(i)
  }
  def createConsumer(i:Int) = {
    new ConsumerClient(i)
  }

  trait NonBlockingClient extends Client {

    protected var queue = createQueue("client")

    var message_counter=0L
    var reconnect_delay = 0L

    sealed trait State

    case class INIT() extends State

    case class CONNECTING(host: String, port: Int, on_complete: ()=>Unit) extends State {
      
      def connect() = {
        val cb = Stomp.callback(host, port)
        cb.dispatchQueue(queue)
        login.foreach(cb.login(_))
        passcode.foreach(cb.passcode(_))
        cb.connect(new Callback[Connection](){
          override def success(connection: Connection) {
            state match {
              case x:CONNECTING =>
                state = CONNECTED(connection)
                on_complete()
                connection.resume()
              case _ => 
                connection.close(null)
            }
          }
          override def failure(value: Throwable) {
            on_failure(value)
          }
        })
      }

      // We may need to delay the connection attempt.
      if( reconnect_delay==0 ) {
        connect
      } else {
        queue.after(5, TimeUnit.SECONDS) {
          if ( this == state ) {
            reconnect_delay=0
            connect
          }
        }
      }

      def close() = {
        state = DISCONNECTED()
      }

      def on_failure(e:Throwable) = {
        if( display_errors ) {
          e.printStackTrace
        }
        error_counter.incrementAndGet
        reconnect_delay = 1000
        close
      }

    }

    case class CONNECTED(val connection:Connection) extends State {

      connection.receive(new Callback[StompFrame](){
        override def failure(value: Throwable) = on_failure(value)
        override def success(value: StompFrame) = on_receive(value)
      })

      def close() = {
        state = CLOSING()
        connection.close(^{
          state = DISCONNECTED()
        })
      }

      def on_failure(e:Throwable) = {
        if( display_errors ) {
          e.printStackTrace
        }
        error_counter.incrementAndGet
        reconnect_delay = 1000
        close
      }

    }
    case class CLOSING() extends State

    case class DISCONNECTED() extends State {
      queue {
        if( state==this ){
          if( done.get ) {
            has_shutdown.countDown
          } else {
            reconnect_action
          }
        }
      }
    }

    var state:State = INIT()

    val has_shutdown = new CountDownLatch(1)
    def reconnect_action:Unit

    def on_failure(e:Throwable) = state match {
      case x:CONNECTING => x.on_failure(e)
      case x:CONNECTED => x.on_failure(e)
      case _ =>
    }

    def start = queue {
      state = DISCONNECTED()
    }

    def queue_check = queue.assertExecuting()

    def open(host: String, port: Int)(on_complete: =>Unit) = {
      assert ( state.isInstanceOf[DISCONNECTED] )
      queue_check
      state = CONNECTING(host, port, ()=>on_complete)
    }

    def close() = {
      queue_check
      state match {
        case x:CONNECTING => x.close
        case x:CONNECTED => x.close
        case _ =>
      }
    }

    def shutdown = {
      assert(done.get)
      queue {
        close
      }
      has_shutdown.await()
    }

    def send(frame:StompFrame)(func: =>Unit) = {
      queue_check
      state match {
        case state:CONNECTED => state.connection.send(frame, new Callback[Void](){
          override def success(value: Void) {
            func
          }
          override def failure(value: Throwable) = on_failure(value)
        })
        case _ =>
      }
    }

    def request(frame:StompFrame)(func: (StompFrame)=>Unit) = {
      queue_check
      state match {
        case state:CONNECTED => state.connection.request(frame, new Callback[StompFrame](){
          override def success(value: StompFrame) {
            func(value)
          }
          override def failure(value: Throwable) = on_failure(value)
        })
        case _ =>
      }
    }

    def receive_suspend = {
      queue_check
      state match {
        case state:CONNECTED => state.connection.suspend()
        case _ =>
      }
    }

    def receive_resume = {
      queue_check
      state match {
        case state:CONNECTED => state.connection.resume()
        case _ =>
      }
    }

    def on_receive(e:StompFrame) = {
    }

    def connect(proc: =>Unit) = {
      queue_check
      if( !done.get ) {
        open(host, port) {
          proc
        }
      }
    }

    def name:String
  }
  
  def header_key(v:String) = ascii(v.split(":")(0))
  def header_value(v:String) = ascii(v.split(":")(1))
  
  val persistent_header_key = header_key(persistent_header)
  val persistent_header_value = header_value(persistent_header)

  class ProducerClient(val id: Int) extends NonBlockingClient {
    val name: String = "producer " + id
    queue.setLabel(name)
    val message_frame = new StompFrame(ascii("SEND"))
    message_frame.addHeader(ascii("destination"),ascii(destination(id)))
    if(persistent) message_frame.addHeader(persistent_header_key,persistent_header_value)
    if(sync_send) message_frame.addHeader(ascii("receipt"), ascii("xxx"))
    headers_for(id).foreach{ x=>
      message_frame.addHeader(header_key(x), header_value(x))
    }
    message_frame.content(message(name))

    override def reconnect_action = {
      connect {
        write_action
      }
    }

    def write_action:Unit = {
      def retry:Unit = {
        if(done.get) {
          close
        } else {
          if( sync_send ) {
            request(message_frame) { resp =>
              producer_counter.incrementAndGet()
              message_counter += 1
              write_completed_action
            }
          } else {
            send(message_frame) {
              producer_counter.incrementAndGet()
              message_counter += 1
              write_completed_action
            }
          }
        }
      }
      retry
    }

    def write_completed_action:Unit = {
      def doit = {
        if(messages_per_connection > 0 && message_counter >= messages_per_connection  ) {
          message_counter = 0
          close
        } else {
          write_action
        }
      }

      if(done.get) {
        close
      } else {
        if(producer_sleep != 0) {
          queue.after(math.abs(producer_sleep), TimeUnit.MILLISECONDS) {
            doit
          }
        } else {
          queue { doit }
        }
      }
    }

  }

  def message(name:String) = {
    val buffer = new StringBuffer(message_size)
    buffer.append("Message from " + name+"\n")
    for( i <- buffer.length to message_size ) {
      buffer.append(('a'+(i%26)).toChar)
    }
    var rc = buffer.toString
    if( rc.length > message_size ) {
      rc.substring(0, message_size)
    } else {
      rc
    }
    ascii(rc)
  }

  class ConsumerClient(val id: Int) extends NonBlockingClient {
    val name: String = "consumer " + id
    queue.setLabel(name)
    val clientAck = ack == "client"

    override def reconnect_action = {
      connect {
        val sub = new StompFrame(ascii("SUBSCRIBE"))
        sub.addHeader(ascii("id"), ascii(consumer_prefix+id))
        sub.addHeader(ascii("ack"), ascii(ack))
        sub.addHeader(ascii("destination"), ascii(destination(id)))
        if(durable) {
          sub.addHeader(ascii("persistent"), ascii("true"))
        }
        if(selector!=null) {
          sub.addHeader(ascii("selector"), ascii(selector))
        }
        send(sub) {
        }
      }
    }

    def index_of(haystack:Array[Byte], needle:Array[Byte]):Int = {
      var i = 0
      while( haystack.length >= i+needle.length ) {
        if( haystack.startsWith(needle, i) ) {
          return i
        }
        i += 1
      }
      return -1
    }


    override def on_receive(msg: StompFrame) = {

      def process_message = {
        if( clientAck ) {
          val msgId = msg.getHeader(ascii("message-id"))
          val ack = new StompFrame(ascii("ACK"))
          ack.addHeader(ascii("message-id"), msgId)
          send(ack){
            consumer_counter.incrementAndGet()
          }
        } else {
          consumer_counter.incrementAndGet()
        }
      }

      if( consumer_sleep != 0 ) {
        if( !clientAck ) {
          receive_suspend
        }
        queue.after(math.abs(consumer_sleep), TimeUnit.MILLISECONDS) {
          if( !clientAck ) {
            receive_resume
          }
          process_message
        }
      } else {
        process_message
      }
    }

  }

}
