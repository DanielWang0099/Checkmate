from langchain_openai import ChatOpenAI
from langchain.prompts import ChatPromptTemplate
from langchain.schema import HumanMessage
from app.core.config import settings
import json
import re

class FactCheckingService:
    def __init__(self):
        self.llm = ChatOpenAI(
            api_key=settings.openai_api_key,
            model="gpt-4-turbo-preview",
            temperature=0.1
        )
    
    async def check_fact(self, claim: str, context: str = None) -> dict:
        prompt = ChatPromptTemplate.from_template("""
        You are a professional fact-checker. Analyze the following claim and provide:
        1. A verdict (True/False/Partially True/Inconclusive)
        2. A confidence score (0.0-1.0)
        3. An explanation
        4. Relevant sources (if available)
        
        Claim: {claim}
        Context: {context}
        
        Return your response in JSON format with keys: verdict, confidence_score, explanation, sources
        Make sure the JSON is valid and properly formatted.
        
        Example format:
        {{
            "verdict": "Partially True",
            "confidence_score": 0.85,
            "explanation": "Your detailed explanation here...",
            "sources": ["https://example.com/source1", "https://example.com/source2"]
        }}
        """)
        
        response = await self.llm.ainvoke(
            prompt.format(claim=claim, context=context or "No additional context provided")
        )
        
        try:
            # Extract JSON from the response
            content = response.content
            # Find JSON in the response using regex
            json_match = re.search(r'\{.*\}', content, re.DOTALL)
            if json_match:
                json_str = json_match.group()
                result = json.loads(json_str)
            else:
                # Fallback if no JSON found
                result = {
                    "verdict": "Inconclusive",
                    "confidence_score": 0.5,
                    "explanation": content,
                    "sources": []
                }
        except (json.JSONDecodeError, AttributeError):
            # Fallback response if JSON parsing fails
            result = {
                "verdict": "Inconclusive",
                "confidence_score": 0.5,
                "explanation": response.content if hasattr(response, 'content') else "Unable to process the claim",
                "sources": []
            }
        
        return result
