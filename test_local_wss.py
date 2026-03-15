#!/usr/bin/env python3
"""
测试本地 WSS 连接
"""

import asyncio
import websockets
import ssl
import sys

async def test_local_wss():
    uri = "wss://localhost:7070"
    
    print(f"正在连接到本地服务器: {uri}")
    
    # 创建 SSL 上下文，信任自签名证书
    ssl_context = ssl.create_default_context()
    ssl_context.check_hostname = False
    ssl_context.verify_mode = ssl.CERT_NONE
    
    try:
        async with websockets.connect(uri, ssl=ssl_context) as websocket:
            print("✅ WSS 连接成功!")
            print(f"服务器地址: {websocket.remote_address}")
            
            # 发送测试消息
            test_message = '{"type": "ping", "data": "test"}'
            await websocket.send(test_message)
            print(f"已发送: {test_message}")
            
            # 等待响应
            try:
                response = await asyncio.wait_for(websocket.recv(), timeout=5.0)
                print(f"收到响应: {response}")
            except asyncio.TimeoutError:
                print("等待响应超时（5秒）")
            
    except ConnectionRefusedError:
        print("❌ 连接被拒绝")
        print("请确保本地服务端正在运行")
        
    except Exception as e:
        print(f"❌ 连接失败: {type(e).__name__}: {e}")

if __name__ == "__main__":
    print("="*50)
    print("本地 WSS 连接测试")
    print("="*50)
    
    try:
        asyncio.run(test_local_wss())
    except KeyboardInterrupt:
        print("\n\n测试已取消")
        sys.exit(0)
