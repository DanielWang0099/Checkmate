#!/usr/bin/env python3
"""
Test Settings Integration - Verify that all settings work correctly with the backend
"""

import asyncio
import aiohttp
import json
from datetime import datetime

async def test_settings_integration():
    """Test the complete settings workflow"""
    base_url = "http://localhost:8000"
    
    async with aiohttp.ClientSession() as session:
        print("🎛️  Testing Checkmate Settings Integration")
        print("=" * 50)
        
        # Test 1: Create session with different strictness levels
        print("\n1. Testing Strictness Levels (Backend Validation):")
        strictness_levels = [0.0, 0.2, 0.4, 0.5, 0.6, 0.8, 1.0]
        
        for strictness in strictness_levels:
            session_data = {
                "sessionType": {"type": "MANUAL"},
                "strictness": strictness,
                "notify": {"details": True, "links": True}
            }
            
            try:
                async with session.post(
                    f"{base_url}/sessions", 
                    json=session_data,
                    headers={"Content-Type": "application/json"}
                ) as response:
                    if response.status == 200:
                        result = await response.json()
                        print(f"   ✅ Strictness {strictness}: Session created (ID: {result.get('sessionId', 'Unknown')[:8]}...)")
                        
                        # Clean up session
                        if result.get('sessionId'):
                            async with session.delete(f"{base_url}/sessions/{result['sessionId']}") as cleanup_response:
                                pass
                    else:
                        print(f"   ❌ Strictness {strictness}: Failed (Status: {response.status})")
            except Exception as e:
                print(f"   ❌ Strictness {strictness}: Error - {e}")
        
        # Test 2: Session types
        print("\n2. Testing Session Types:")
        session_types = [
            {"type": "MANUAL", "description": "Manual session"},
            {"type": "TIME", "minutes": 60, "description": "1-hour time-boxed session"},
            {"type": "TIME", "minutes": 180, "description": "3-hour time-boxed session"},
            {"type": "ACTIVITY", "description": "Activity-based session"}
        ]
        
        for session_type_config in session_types:
            session_data = {
                "sessionType": session_type_config,
                "strictness": 0.5,
                "notify": {"details": True, "links": True}
            }
            
            try:
                async with session.post(
                    f"{base_url}/sessions", 
                    json=session_data,
                    headers={"Content-Type": "application/json"}
                ) as response:
                    if response.status == 200:
                        result = await response.json()
                        print(f"   ✅ {session_type_config['description']}: Created successfully")
                        
                        # Clean up session
                        if result.get('sessionId'):
                            async with session.delete(f"{base_url}/sessions/{result['sessionId']}") as cleanup_response:
                                pass
                    else:
                        print(f"   ❌ {session_type_config['description']}: Failed (Status: {response.status})")
            except Exception as e:
                print(f"   ❌ {session_type_config['description']}: Error - {e}")
        
        # Test 3: Notification settings
        print("\n3. Testing Notification Settings:")
        notification_configs = [
            {"details": True, "links": True, "description": "Full notifications"},
            {"details": True, "links": False, "description": "Details only"},
            {"details": False, "links": True, "description": "Links only"},
            {"details": False, "links": False, "description": "Minimal notifications"}
        ]
        
        for notify_config in notification_configs:
            session_data = {
                "sessionType": {"type": "MANUAL"},
                "strictness": 0.5,
                "notify": notify_config
            }
            
            try:
                async with session.post(
                    f"{base_url}/sessions", 
                    json=session_data,
                    headers={"Content-Type": "application/json"}
                ) as response:
                    if response.status == 200:
                        result = await response.json()
                        print(f"   ✅ {notify_config['description']}: Created successfully")
                        
                        # Verify settings are preserved
                        returned_settings = result.get('settings', {})
                        returned_notify = returned_settings.get('notify', {})
                        
                        if (returned_notify.get('details') == notify_config['details'] and 
                            returned_notify.get('links') == notify_config['links']):
                            print(f"      ✅ Settings preserved correctly")
                        else:
                            print(f"      ⚠️  Settings mismatch: Expected {notify_config}, Got {returned_notify}")
                        
                        # Clean up session
                        if result.get('sessionId'):
                            async with session.delete(f"{base_url}/sessions/{result['sessionId']}") as cleanup_response:
                                pass
                    else:
                        print(f"   ❌ {notify_config['description']}: Failed (Status: {response.status})")
            except Exception as e:
                print(f"   ❌ {notify_config['description']}: Error - {e}")
        
        # Test 4: Edge cases and validation
        print("\n4. Testing Edge Cases:")
        edge_cases = [
            {
                "name": "Invalid strictness (negative)",
                "data": {"sessionType": {"type": "MANUAL"}, "strictness": -0.1, "notify": {"details": True, "links": True}},
                "expect_fail": True
            },
            {
                "name": "Invalid strictness (too high)",
                "data": {"sessionType": {"type": "MANUAL"}, "strictness": 1.1, "notify": {"details": True, "links": True}},
                "expect_fail": True
            },
            {
                "name": "TIME session without minutes",
                "data": {"sessionType": {"type": "TIME"}, "strictness": 0.5, "notify": {"details": True, "links": True}},
                "expect_fail": False  # Should work with default
            },
            {
                "name": "TIME session with 0 minutes",
                "data": {"sessionType": {"type": "TIME", "minutes": 0}, "strictness": 0.5, "notify": {"details": True, "links": True}},
                "expect_fail": True
            }
        ]
        
        for test_case in edge_cases:
            try:
                async with session.post(
                    f"{base_url}/sessions", 
                    json=test_case["data"],
                    headers={"Content-Type": "application/json"}
                ) as response:
                    if test_case["expect_fail"]:
                        if response.status != 200:
                            print(f"   ✅ {test_case['name']}: Correctly rejected (Status: {response.status})")
                        else:
                            print(f"   ⚠️  {test_case['name']}: Should have been rejected but was accepted")
                            result = await response.json()
                            if result.get('sessionId'):
                                async with session.delete(f"{base_url}/sessions/{result['sessionId']}") as cleanup_response:
                                    pass
                    else:
                        if response.status == 200:
                            print(f"   ✅ {test_case['name']}: Correctly accepted")
                            result = await response.json()
                            if result.get('sessionId'):
                                async with session.delete(f"{base_url}/sessions/{result['sessionId']}") as cleanup_response:
                                    pass
                        else:
                            print(f"   ❌ {test_case['name']}: Should have been accepted but was rejected (Status: {response.status})")
            except Exception as e:
                if test_case["expect_fail"]:
                    print(f"   ✅ {test_case['name']}: Correctly failed with error - {e}")
                else:
                    print(f"   ❌ {test_case['name']}: Unexpected error - {e}")

        print("\n✅ Settings integration testing complete!")

if __name__ == "__main__":
    asyncio.run(test_settings_integration())
