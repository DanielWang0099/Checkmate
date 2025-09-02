"""AWS Bedrock service for LLM interactions."""

import json
import asyncio
from typing import Dict, Any, List, Optional, Union
import boto3
from botocore.exceptions import ClientError
from app.core.config import settings
from app.models.schemas import (
    SessionMemory, FrameBundle, ManagerResponse, FactCheckResult,
    AgentContext, MediaType
)
from app.services.tools import tools


class BedrockService:
    """Service for interacting with AWS Bedrock LLMs."""
    
    def __init__(self):
        self.client = None
        self._initialize_client()
    
    def _initialize_client(self):
        """Initialize Bedrock client with credentials."""
        try:
            self.client = boto3.client(
                'bedrock-runtime',
                region_name=settings.aws_region,
                aws_access_key_id=settings.aws_access_key_id,
                aws_secret_access_key=settings.aws_secret_access_key,
                aws_session_token=settings.aws_session_token
            )
        except Exception as e:
            print(f"Failed to initialize Bedrock client: {e}")
            self.client = None
    
    async def invoke_model(
        self,
        model_id: str,
        messages: List[Dict[str, Any]],
        system_prompt: Optional[str] = None,
        max_tokens: int = 4000,
        temperature: float = 0.1,
        tools_config: Optional[List[Dict[str, Any]]] = None
    ) -> Dict[str, Any]:
        """Invoke a Bedrock model with the given messages."""
        if not self.client:
            raise Exception("Bedrock client not initialized")
        
        # Prepare the request body for Claude 3.5 Sonnet
        body = {
            "anthropic_version": "bedrock-2023-05-31",
            "max_tokens": max_tokens,
            "temperature": temperature,
            "messages": messages
        }
        
        if system_prompt:
            body["system"] = system_prompt
        
        if tools_config:
            body["tools"] = tools_config
        
        try:
            response = self.client.invoke_model(
                modelId=model_id,
                body=json.dumps(body),
                contentType="application/json"
            )
            
            response_body = json.loads(response['body'].read())
            return response_body
        
        except ClientError as e:
            raise Exception(f"Bedrock API error: {e}")
        except Exception as e:
            raise Exception(f"Model invocation failed: {e}")
    
    async def process_tool_calls(self, tool_calls: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Process tool calls and return results."""
        results = []
        
        for tool_call in tool_calls:
            tool_name = tool_call.get("name")
            tool_input = tool_call.get("input", {})
            
            try:
                result = await tools.call_tool(tool_name, **tool_input)
                results.append({
                    "tool_use_id": tool_call.get("id"),
                    "type": "tool_result",
                    "content": json.dumps(result, default=str)
                })
            except Exception as e:
                results.append({
                    "tool_use_id": tool_call.get("id"),
                    "type": "tool_result",
                    "content": f"Error: {str(e)}",
                    "is_error": True
                })
        
        return results


class ManagerAgent:
    """Manager agent for orchestrating fact-checking sessions."""
    
    SYSTEM_PROMPT = """You are Checkmate's MANAGER agent. Maintain JSON memory across turns using this schema:
{
  "settings": {"sessionType":{"type":"TIME|MANUAL|ACTIVITY","minutes":int?},
               "strictness":number,
               "notify":{"details":bool,"links":bool}},
  "timeline":[{"t":"mm:ss","event":string}],
  "currentActivity":{"id":string,"app":string,"media":"text|text+image|short-video|long-video|audio","desc":string},
  "pastContents":{"content_n":{ "app":string,"media":string,"desc":string,"hasVideo":bool,"hasAudio":bool,"publisher":string?,"topic":string?,"contextNotes":string }},
  "lastClaimsChecked":[{"claim":string,"status":"supported|contested|misleading|uncertain","sources":[{"url":string,"tier":"A|B|C"}]}]
}

Use STRICTNESS GATES and TRUST TIERS as specified. Never invent sources. Minimize disruption.
SESSION END (Activity): propose end if stable new activity ≥90s and no new check-worthy content in last 60s.
Your outputs MUST match the OUTPUT JSON schema exactly.

STRICTNESS LEVELS:
- 0.0-0.2: Very conservative, only flag egregious falsehoods
- 0.4-0.5: Balanced approach
- 0.6-0.8: More proactive, flag questionable content
- 1.0: Flag any potentially misleading content

SOURCE TIERS:
- Tier A: Wikipedia, Britannica, Nature, Science.org, WHO, CDC
- Tier B: Reuters, AP, BBC, NPR, PBS, FactCheck.org, Snopes
- Tier C: YouTube, Twitter, Facebook, TikTok

ACTIVITY DETECTION:
- Monitor app changes and content switches
- Be patient with momentary switches (WhatsApp, notifications)
- Confirm stable activity changes before updating memory
- Consider context: same app but different content = potential new activity"""
    
    def __init__(self, bedrock_service: BedrockService):
        self.bedrock = bedrock_service
    
    def _build_user_prompt(self, memory: SessionMemory, frame_bundle: FrameBundle) -> str:
        """Build the user prompt for the manager agent."""
        return f"""MEMORY:
{memory.model_dump_json(by_alias=True, indent=2)}

INPUT:
{frame_bundle.model_dump_json(by_alias=True, indent=2)}

INSTRUCTIONS:
1) Update memory.timeline with inferred actions (mm:ss from session start).
2) Decide media type (text / text+image / short-video / long-video).
3) Build agentContext (see per-media formats below).
4) Apply STRICTNESS GATES, synthesize notifications (color policy).
5) Set endSession if rules met.

Return STRICT JSON:
{{
  "updatedMemory": {{...}},
  "route": "text|text+image|short-video|long-video|none",
  "agentContext": {{...}},
  "endSession": false,
  "notifications": [
    {{"color":"green|yellow|red","shortText":string,"details":string?,"sources":[{{"title":string,"url":string,"tier":"A|B|C"}}],"confidence":number,"severity":number,"shouldNotify":bool}}
  ]
}}"""
    
    async def process_frame(self, memory: SessionMemory, frame_bundle: FrameBundle) -> ManagerResponse:
        """Process a frame bundle and return manager response."""
        messages = [
            {
                "role": "user",
                "content": self._build_user_prompt(memory, frame_bundle)
            }
        ]
        
        response = await self.bedrock.invoke_model(
            model_id=settings.bedrock_manager_model,
            messages=messages,
            system_prompt=self.SYSTEM_PROMPT,
            temperature=0.1
        )
        
        # Parse the response
        content = response.get("content", [])
        if content and content[0].get("type") == "text":
            try:
                result_json = json.loads(content[0]["text"])
                return ManagerResponse(**result_json)
            except json.JSONDecodeError as e:
                raise Exception(f"Failed to parse manager response: {e}")
        
        raise Exception("Invalid response format from manager agent")


class MediaAgent:
    """Base class for media-specific fact-checking agents."""
    
    def __init__(self, bedrock_service: BedrockService, media_type: MediaType):
        self.bedrock = bedrock_service
        self.media_type = media_type
        self.tools_config = tools.get_tool_descriptions()
    
    async def fact_check(self, agent_context: AgentContext) -> FactCheckResult:
        """Perform fact-checking for the given context."""
        raise NotImplementedError("Subclasses must implement fact_check method")
    
    async def _invoke_with_tools(self, system_prompt: str, user_prompt: str) -> FactCheckResult:
        """Invoke model with tool calling capability."""
        messages = [{"role": "user", "content": user_prompt}]
        
        max_iterations = 3
        iteration = 0
        
        while iteration < max_iterations:
            response = await self.bedrock.invoke_model(
                model_id=settings.bedrock_agent_model,
                messages=messages,
                system_prompt=system_prompt,
                tools_config=self.tools_config
            )
            
            content = response.get("content", [])
            if not content:
                break
            
            # Check if there are tool calls
            tool_calls = [item for item in content if item.get("type") == "tool_use"]
            
            if tool_calls:
                # Process tool calls
                tool_results = await self.bedrock.process_tool_calls(tool_calls)
                
                # Add assistant message with tool calls
                messages.append({
                    "role": "assistant",
                    "content": content
                })
                
                # Add tool results
                messages.append({
                    "role": "user", 
                    "content": tool_results
                })
                
                iteration += 1
            else:
                # No more tool calls, get final response
                text_content = next((item["text"] for item in content if item.get("type") == "text"), "")
                try:
                    result_json = json.loads(text_content)
                    return FactCheckResult(**result_json)
                except json.JSONDecodeError:
                    # Try to extract JSON from the text
                    import re
                    json_match = re.search(r'\{.*\}', text_content, re.DOTALL)
                    if json_match:
                        try:
                            result_json = json.loads(json_match.group())
                            return FactCheckResult(**result_json)
                        except:
                            pass
                    
                    # Fallback: create empty result
                    return FactCheckResult(
                        claims=[],
                        notes=f"Failed to parse agent response: {text_content}"
                    )
        
        return FactCheckResult(claims=[], notes="Maximum tool iterations reached")


class TextAgent(MediaAgent):
    """Agent for fact-checking text content."""
    
    SYSTEM_PROMPT = """You verify factual claims in on-screen text.
Extract atomic claims, verify with tools, and return structured results.
Prefer primary sources and Tier-A/B. Quote exact matches when possible.
Avoid opinions; focus on factual assertions.

TOOLS: web_search, fetch_url, claim_check

OUTPUT JSON:
{"claims":[{"text":string,"label":"supported|misleading|false|uncertain","confidence":0..1,"severity":0..1,"sources":[{"title":string,"url":string,"tier":"A|B|C","directQuoteMatch":bool}]}],"notes":string}"""
    
    def __init__(self, bedrock_service: BedrockService):
        super().__init__(bedrock_service, MediaType.TEXT)
    
    async def fact_check(self, agent_context: AgentContext) -> FactCheckResult:
        user_prompt = f"""Agent context: {agent_context.model_dump_json(by_alias=True, indent=2)}

Please fact-check the provided text content. Extract factual claims and verify them using available tools."""
        
        return await self._invoke_with_tools(self.SYSTEM_PROMPT, user_prompt)


class TextImageAgent(MediaAgent):
    """Agent for fact-checking text with images."""
    
    SYSTEM_PROMPT = """You verify text in the presence of an image.
Check caption-image consistency, prior uses of the image, and numeric/scientific claims.
TOOLS: reverse_image_search, web_search, fetch_url, claim_check
OUTPUT JSON as in Text agent."""
    
    def __init__(self, bedrock_service: BedrockService):
        super().__init__(bedrock_service, MediaType.TEXT_IMAGE)
    
    async def fact_check(self, agent_context: AgentContext) -> FactCheckResult:
        user_prompt = f"""Agent context: {agent_context.model_dump_json(by_alias=True, indent=2)}

Please fact-check the text content and verify the image consistency. Use reverse image search to check if the image has been used in different contexts."""
        
        return await self._invoke_with_tools(self.SYSTEM_PROMPT, user_prompt)


class VideoAgent(MediaAgent):
    """Agent for fact-checking video content (both short and long-form)."""
    
    SYSTEM_PROMPT = """You verify claims in video content.
For short videos: Segment recent content into claims; use platform metadata to avoid satire/fiction false positives.
For long videos: Use metadata for channel credibility; align transcript to claims; avoid taking quotes out of context.
Handle rapid topic switches gracefully.

TOOLS: yt_meta, tiktok_meta, web_search, fetch_url, claim_check
OUTPUT JSON as in Text agent."""
    
    def __init__(self, bedrock_service: BedrockService):
        super().__init__(bedrock_service, MediaType.LONG_VIDEO)  # Can handle both
    
    async def fact_check(self, agent_context: AgentContext) -> FactCheckResult:
        user_prompt = f"""Agent context: {agent_context.model_dump_json(by_alias=True, indent=2)}

Please fact-check the video content. Use platform metadata to understand context and credibility. Be aware of potential rapid content changes in short-form videos."""
        
        return await self._invoke_with_tools(self.SYSTEM_PROMPT, user_prompt)


class AgentOrchestrator:
    """Orchestrates the manager and media agents."""
    
    def __init__(self):
        self.bedrock = BedrockService()
        self.manager = ManagerAgent(self.bedrock)
        self.text_agent = TextAgent(self.bedrock)
        self.text_image_agent = TextImageAgent(self.bedrock)
        self.video_agent = VideoAgent(self.bedrock)
    
    async def process_frame(self, memory: SessionMemory, frame_bundle: FrameBundle) -> ManagerResponse:
        """Process a frame bundle through the complete agent workflow."""
        # Step 1: Manager processes frame and decides routing
        manager_response = await self.manager.process_frame(memory, frame_bundle)
        
        # Step 2: Route to appropriate media agent if needed
        if manager_response.route != "none" and manager_response.agent_context:
            try:
                if manager_response.route == MediaType.TEXT:
                    fact_check_result = await self.text_agent.fact_check(manager_response.agent_context)
                elif manager_response.route == MediaType.TEXT_IMAGE:
                    fact_check_result = await self.text_image_agent.fact_check(manager_response.agent_context)
                elif manager_response.route in [MediaType.SHORT_VIDEO, MediaType.LONG_VIDEO]:
                    fact_check_result = await self.video_agent.fact_check(manager_response.agent_context)
                else:
                    fact_check_result = None
                
                # Step 3: Manager synthesizes final notifications based on agent results
                if fact_check_result:
                    # Update manager response with fact-check results
                    # This would involve another manager call to synthesize notifications
                    pass
                    
            except Exception as e:
                print(f"Media agent error: {e}")
                # Continue with manager response even if media agent fails
        
        return manager_response


# Global orchestrator instance
orchestrator = AgentOrchestrator()
