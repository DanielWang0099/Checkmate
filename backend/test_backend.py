import requests
import json

def test_backend():
    """Test if the backend is running and accessible"""
    try:
        # Test root endpoint
        response = requests.get("http://localhost:8000")
        print("✅ Backend is running!")
        print(f"Response: {response.json()}")
        
        # Test fact-check endpoint
        test_data = {
            "claim": "The Earth is round",
            "context": "Basic geography test"
        }
        
        print("\n🧪 Testing fact-check endpoint...")
        response = requests.post("http://localhost:8000/api/v1/fact-check", json=test_data)
        
        if response.status_code == 200:
            result = response.json()
            print("✅ Fact-check endpoint working!")
            print(f"Claim: {result['claim']}")
            print(f"Verdict: {result['verdict']}")
            print(f"Confidence: {result['confidence_score']:.2f}")
            print(f"Explanation: {result['explanation'][:100]}...")
        else:
            print(f"❌ Fact-check failed with status: {response.status_code}")
            print(f"Error: {response.text}")
            
    except requests.exceptions.ConnectionError:
        print("❌ Cannot connect to backend. Make sure it's running on http://localhost:8000")
    except Exception as e:
        print(f"❌ Error: {e}")

if __name__ == "__main__":
    test_backend()
