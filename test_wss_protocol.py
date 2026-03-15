#!/usr/bin/env python3
"""
测试 WSS 连接 - 使用正确的消息格式
"""

import asyncio
import websockets
import ssl
import json
import sys

async def test_wss_with_correct_protocol():
    uri = "wss://localhost:7070"
    
    print(f"正在连接到: {uri}")
    
    # 创建 SSL 上下文，信任自签名证书
    ssl_context = ssl.create_default_context()
    ssl_context.check_hostname = False
    ssl_context.verify_mode = ssl.CERT_NONE
    
    try:
        async with websockets.connect(uri, ssl=ssl_context) as websocket:
            print("✅ WSS 连接成功!")
            print(f"服务器地址: {websocket.remote_address}")
            
            # 发送 SERVER_REGISTER 消息（正确的协议格式）
            register_message = {
                "type": "SERVER_REGISTER",
                "device_id": "test-device-001",
                "device_name": "Test Device"
            }
            message_json = json.dumps(register_message)
            await websocket.send(message_json)
            print(f"已发送: {message_json}")
            
            # 等待响应
            try:
                response = await asyncio.wait_for(websocket.recv(), timeout=5.0)
                print(f"收到响应: {response}")
                
                # 解析响应
                response_data = json.loads(response)
                print(f"响应类型: {response_data.get('type')}")
                
            except asyncio.TimeoutError:
                print("等待响应超时（5秒）")
            
    except ConnectionRefusedError:
        print("❌ 连接被拒绝")
        print("请确保服务端正在运行")
        
    except Exception as e:
        print(f"❌ 连接失败: {type(e).__name__}: {e}")

async def test_remote_wss():
    uri = "wss://nas.smarthome2020.top:7070"
    
    print(f"正在连接到远程服务器: {uri}")
    
    # 创建 SSL 上下文，信任自签名证书
    ssl_context = ssl.create_default_context()
    ssl_context.check_hostname = False
    ssl_context.verify_mode = ssl.CERT_NONE
    
    try:
        async with websockets.connect(uri, ssl=ssl_context) as websocket:
            print("✅ 远程 WSS 连接成功!")
            print(f"服务器地址: {websocket.remote_address}")
            
            # 发送测试消息
            register_message = {
                "type": "SERVER_REGISTER",
                "device_id": "test-device-001",
                "device_name": "Test Device"
            }
            message_json = json.dumps(register_message)
            await websocket.send(message_json)
            print(f"已发送: {message_json}")
            
            # 等待响应
            try:
                response = await asyncio.wait_for(websocket.recv(), timeout=5.0)
                print(f"收到响应: {response}")
            except asyncio.TimeoutError:
                print("等待响应超时（5秒）")
            
    except ConnectionRefusedError:
        print("❌ 连接被拒绝")
        print("请检查:")
        print("1. 远程服务器是否正在运行")
        print("2. 端口 7070 是否开放")
        print("3. 防火墙设置")
        
    except Exception as e:
        print(f"❌ 连接失败: {type(e).__name__}: {e}")

if __name__ == "__main__":
    print("="*50)
    print("WSS 连接测试（使用正确协议）")
    print("="*50)
    
    # 先测试本地
    print("\n【测试 1: 本地连接】")
    try:
        asyncio.run(test_wss_with_correct_protocol())
    except KeyboardInterrupt:
        pass
    
    # 再测试远程
    print("\n【测试 2: 远程连接】")
    try:
        asyncio.run(test_remote_wss())
    except KeyboardInterrupt:
        pass
    
    print("\n测试完成")
