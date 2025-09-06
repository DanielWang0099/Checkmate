"""AWS Bedrock service for LLM interactions."""

import json
import asyncio
import logging
from typing import Dict, Any, List, Optional, Union
import boto3
from botocore.exceptions import ClientError
from app.core.config import settings
from app.models.schemas import (
    SessionMemory, FrameBundle, ManagerResponse, FactCheckResult,
    AgentContext, TextImageAgentContext, VideoAgentContext, AgentContextUnion,
    MediaType, NotificationColor, ErrorResponse, ErrorType, ErrorSeverity
)
from app.services.tools import tools
from app.core.config import settings, STRICTNESS_THRESHOLDS, SOURCE_TIERS

logger = logging.getLogger(__name__)  # ✅ CRITICAL FIX: Added missing logger


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
        """Process tool calls and return results with proper error handling."""
        results = []
        
        for tool_call in tool_calls:
            tool_name = tool_call.get("name")
            tool_input = tool_call.get("input", {})
            tool_use_id = tool_call.get("id")
            
            try:
                result = await tools.call_tool(tool_name, **tool_input)
                results.append({
                    "tool_use_id": tool_use_id,
                    "type": "tool_result",
                    "content": [{"type": "text", "text": json.dumps(result, default=str)}]
                })
            except Exception as e:
                # Determine error type based on exception
                if "network" in str(e).lower() or "timeout" in str(e).lower():
                    error_type = "network_error"
                    fallback_message = "Unable to verify now due to network issues. Continuing with local analysis."
                elif "rate limit" in str(e).lower():
                    error_type = "rate_limit"
                    fallback_message = "Rate limit reached. Verification temporarily limited."
                elif "blocked" in str(e).lower() or "forbidden" in str(e).lower():
                    error_type = "privacy_block"
                    fallback_message = "Unable to verify now due to privacy restrictions."
                else:
                    error_type = "tool_error"
                    fallback_message = f"Tool {tool_name} unavailable. Continuing with limited verification."
                
                results.append({
                    "tool_use_id": tool_use_id,
                    "type": "tool_result",
                    "content": [{"type": "text", "text": json.dumps({
                        "error_type": error_type,
                        "message": fallback_message,
                        "details": str(e)
                    }, default=str)}],
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
                
                # Handle polymorphic AgentContext with proper validation
                if "agentContext" in result_json and result_json["agentContext"]:
                    route = result_json.get("route")
                    agent_context_data = result_json["agentContext"]
                    
                    # CRITICAL FIX: Validate and construct context based on data structure AND route
                    validated_context = self._validate_and_construct_agent_context(
                        route, agent_context_data
                    )
                    result_json["agentContext"] = validated_context
                
                return ManagerResponse(**result_json)
            except json.JSONDecodeError as e:
                raise Exception(f"Failed to parse manager response: {e}")
            except Exception as e:
                raise Exception(f"Failed to build AgentContext: {e}")
        
        raise Exception("Invalid response format from manager agent")
    
    def _validate_and_construct_agent_context(
        self, route: str, agent_context_data: Dict[str, Any]
    ) -> AgentContextUnion:
        """
        Validate and construct the appropriate AgentContext based on route and data.
        
        CRITICAL: This ensures type safety and prevents runtime errors.
        """
        # Add context_type discriminator based on route
        if route == MediaType.TEXT_IMAGE:
            agent_context_data["contextType"] = "text_image"
            # Validate required fields
            if "imageRef" not in agent_context_data:
                raise ValueError(f"TEXT_IMAGE route requires 'imageRef' field in agent context")
            return TextImageAgentContext(**agent_context_data)
            
        elif route in [MediaType.SHORT_VIDEO, MediaType.LONG_VIDEO]:
            agent_context_data["contextType"] = "video"
            # Validate required fields
            if "transcriptDelta" not in agent_context_data:
                raise ValueError(f"VIDEO route requires 'transcriptDelta' field in agent context")
            return VideoAgentContext(**agent_context_data)
            
        elif route == MediaType.TEXT:
            agent_context_data["contextType"] = "text"
            return AgentContext(**agent_context_data)
            
        else:
            raise ValueError(f"Invalid route '{route}' for agent context construction")


class MediaAgent:
    """Base class for media-specific fact-checking agents."""
    
    def __init__(self, bedrock_service: BedrockService, media_type: MediaType):
        self.bedrock = bedrock_service
        self.media_type = media_type
        self.tools_config = tools.get_tool_descriptions()
    
    async def fact_check(self, agent_context: AgentContextUnion, strictness: float = 0.5) -> FactCheckResult:
        """Perform fact-checking for the given context with strictness policy."""
        raise NotImplementedError("Subclasses must implement fact_check method")
    
    def apply_strictness_filter(self, fact_check_result: FactCheckResult, strictness: float) -> FactCheckResult:
        """Filter and adjust results based on strictness level."""
        thresholds = STRICTNESS_THRESHOLDS.get(strictness, STRICTNESS_THRESHOLDS[0.5])
        
        filtered_claims = []
        for claim in fact_check_result.claims:
            # Only include claims that meet strictness confidence threshold
            if claim.confidence >= thresholds["min_confidence"]:
                # Check source requirements
                has_sufficient_sources = self._validate_sources(claim.sources, thresholds)
                if has_sufficient_sources:
                    filtered_claims.append(claim)
        
        return FactCheckResult(
            claims=filtered_claims,
            notes=fact_check_result.notes,
            summary=fact_check_result.summary,
            sources=fact_check_result.sources
        )
    
    def _validate_sources(self, sources: List, thresholds: Dict[str, Any]) -> bool:
        """Validate if sources meet strictness requirements."""
        if thresholds["min_sources"] == 0:
            return True
            
        tier_a_count = sum(1 for source in sources if source.tier == "A")
        tier_b_count = sum(1 for source in sources if source.tier == "B") 
        tier_c_count = sum(1 for source in sources if source.tier == "C")
        
        # Check tier A/B requirements
        if tier_a_count >= 1 or tier_b_count >= 1:
            return True
            
        # Check if tier C is allowed and sufficient
        if thresholds["tier_c_allowed"] and tier_c_count >= thresholds["min_sources"]:
            return True
            
        return False
    
    @staticmethod
    def classify_source_tier(url: str) -> str:
        """Classify source tier based on URL domain."""
        domain = url.lower()
        
        for tier_a_domain in SOURCE_TIERS["A"]:
            if tier_a_domain in domain:
                return "A"
                
        for tier_b_domain in SOURCE_TIERS["B"]:
            if tier_b_domain in domain:
                return "B"
                
        for tier_c_domain in SOURCE_TIERS["C"]:
            if tier_c_domain in domain:
                return "C"
                
        # Default to tier B for unknown but established domains
        return "B"
    
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
                    
                    # Fallback: create empty result with all required fields
                    return FactCheckResult(
                        claims=[],
                        notes=f"Failed to parse agent response: {text_content}",
                        summary="Unable to parse fact-check response",
                        sources=[]
                    )
        
        return FactCheckResult(
            claims=[], 
            notes="Maximum tool iterations reached",
            summary="Fact-checking incomplete due to iteration limit",
            sources=[]
        )


class TextAgent(MediaAgent):
    """Agent for fact-checking text content."""
    
    SYSTEM_PROMPT = """You verify factual claims in on-screen text.
Extract atomic claims, verify with tools, and return structured results.
Prefer primary sources and Tier-A/B. Quote exact matches when possible.
Avoid opinions; focus on factual assertions.

TOOLS: web_search, fetch_url, claim_check

SOURCE TIERS:
- Tier A: Wikipedia, Britannica, Nature, Science.org, WHO, CDC
- Tier B: Reuters, AP, BBC, NPR, PBS, FactCheck.org, Snopes  
- Tier C: YouTube, Twitter, Facebook, TikTok

OUTPUT JSON:
{"claims":[{"text":string,"label":"supported|misleading|false|uncertain","confidence":0..1,"severity":0..1,"sources":[{"title":string,"url":string,"tier":"A|B|C","directQuoteMatch":bool}]}],"notes":string,"summary":string,"sources":[{"title":string,"url":string,"tier":"A|B|C"}]}

CRITICAL: Always include 'summary' (brief overview) and 'sources' (all sources used) fields."""
    
    def __init__(self, bedrock_service: BedrockService):
        super().__init__(bedrock_service, MediaType.TEXT)
    
    async def fact_check(self, agent_context: AgentContextUnion, strictness: float = 0.5) -> FactCheckResult:
        """Perform fact-checking for text content with type validation."""
        # CRITICAL: Validate context type matches agent capability
        if not isinstance(agent_context, AgentContext) or agent_context.context_type != "text":
            raise ValueError(f"TextAgent requires AgentContext with context_type='text', got {type(agent_context)} with context_type='{getattr(agent_context, 'context_type', 'unknown')}'")
        
        # Add strictness context to prompt
        strictness_guidance = f"""STRICTNESS LEVEL: {strictness}
Confidence threshold: {STRICTNESS_THRESHOLDS.get(strictness, STRICTNESS_THRESHOLDS[0.5])['min_confidence']}
Apply appropriate fact-checking rigor for this strictness level."""
        
        user_prompt = f"""{strictness_guidance}

Agent context: {agent_context.model_dump_json(by_alias=True, indent=2)}

Please fact-check the provided text content. Extract factual claims and verify them using available tools."""
        
        result = await self._invoke_with_tools(self.SYSTEM_PROMPT, user_prompt)
        return self.apply_strictness_filter(result, strictness)


class TextImageAgent(MediaAgent):
    """Agent for fact-checking text with images."""
    
    SYSTEM_PROMPT = """You verify text in the presence of an image.
Check caption-image consistency, prior uses of the image, and numeric/scientific claims.
Your main concern is that caption of image matches the image and similarity reverse search 
so that the image used is in the appropriate context.

TOOLS: reverse_image_search, web_search, fetch_url, claim_check

SOURCE TIERS:
- Tier A: Wikipedia, Britannica, Nature, Science.org, WHO, CDC
- Tier B: Reuters, AP, BBC, NPR, PBS, FactCheck.org, Snopes
- Tier C: YouTube, Twitter, Facebook, TikTok

OUTPUT JSON:
{"claims":[{"text":string,"label":"supported|misleading|false|uncertain","confidence":0..1,"severity":0..1,"sources":[{"title":string,"url":string,"tier":"A|B|C","directQuoteMatch":bool}]}],"notes":string,"summary":string,"sources":[{"title":string,"url":string,"tier":"A|B|C"}]}

CRITICAL: Always include 'summary' (brief overview) and 'sources' (all sources used) fields."""
    
    def __init__(self, bedrock_service: BedrockService):
        super().__init__(bedrock_service, MediaType.TEXT_IMAGE)
    
    async def fact_check(self, agent_context: AgentContextUnion, strictness: float = 0.5) -> FactCheckResult:
        """Perform fact-checking for text+image content with type validation."""
        # CRITICAL: Validate context type matches agent capability
        if not isinstance(agent_context, TextImageAgentContext) or agent_context.context_type != "text_image":
            raise ValueError(f"TextImageAgent requires TextImageAgentContext with context_type='text_image', got {type(agent_context)} with context_type='{getattr(agent_context, 'context_type', 'unknown')}'")
        
        # Validate required fields
        if not agent_context.image_ref:
            raise ValueError("TextImageAgentContext requires non-empty image_ref field")
        
        # ✅ CRITICAL ENHANCEMENT: Get actual image URL from S3 for tool usage
        try:
            from app.services.s3_service import s3_service
            image_url = await s3_service.get_agent_context_url(agent_context.image_ref)
            logger.info(f"Retrieved image URL for AgentContext: {agent_context.image_ref}")
        except Exception as e:
            logger.warning(f"Failed to get S3 URL for image_ref {agent_context.image_ref}: {e}")
            # Fallback: use image_ref as-is (might be external URL)
            image_url = agent_context.image_ref
        
        # Add strictness context to prompt
        strictness_guidance = f"""STRICTNESS LEVEL: {strictness}
Confidence threshold: {STRICTNESS_THRESHOLDS.get(strictness, STRICTNESS_THRESHOLDS[0.5])['min_confidence']}
Focus on image-text consistency verification with appropriate rigor."""
        
        user_prompt = f"""{strictness_guidance}

Agent context: {agent_context.model_dump_json(by_alias=True, indent=2)}

Please fact-check the text content and verify the image consistency. Use reverse image search to check if the image has been used in different contexts.
Image reference: {agent_context.image_ref}
Actual image URL for tools: {image_url}

CRITICAL: Use the actual image URL for reverse image search and analysis tools."""
        
        result = await self._invoke_with_tools(self.SYSTEM_PROMPT, user_prompt)
        return self.apply_strictness_filter(result, strictness)


class VideoAgent(MediaAgent):
    """Agent for fact-checking video content (both short and long-form)."""
    
    SYSTEM_PROMPT = """You verify claims in video content.
For short videos: Segment recent content into claims; use platform metadata to avoid satire/fiction false positives.
For long videos: Use metadata for channel credibility; align transcript to claims; avoid taking quotes out of context.
Handle rapid topic switches gracefully. Focus on extracting as much context as possible: 
if URL is identified, use tools such as Youtube API to retrieve info about user channel content, 
hashtags and content type to avoid scenarios such as marking a fact said in a science fiction 
channel as false (obviously false as it is science fiction). Beware fast content change. 
Efficiently separate what might be from one short/reel from another one. The LLM should decide 
if it is in a rapid changing environment (shorts or reels scrolling), or in a long video.

TOOLS: yt_meta, tiktok_meta, web_search, fetch_url, claim_check

SOURCE TIERS:
- Tier A: Wikipedia, Britannica, Nature, Science.org, WHO, CDC
- Tier B: Reuters, AP, BBC, NPR, PBS, FactCheck.org, Snopes
- Tier C: YouTube, Twitter, Facebook, TikTok

OUTPUT JSON:
{"claims":[{"text":string,"label":"supported|misleading|false|uncertain","confidence":0..1,"severity":0..1,"sources":[{"title":string,"url":string,"tier":"A|B|C","directQuoteMatch":bool}]}],"notes":string,"summary":string,"sources":[{"title":string,"url":string,"tier":"A|B|C"}]}

CRITICAL: Always include 'summary' (brief overview) and 'sources' (all sources used) fields."""
    
    def __init__(self, bedrock_service: BedrockService):
        super().__init__(bedrock_service, MediaType.LONG_VIDEO)  # Can handle both
    
    async def fact_check(self, agent_context: AgentContextUnion, strictness: float = 0.5) -> FactCheckResult:
        """Perform fact-checking for video content with type validation."""
        # CRITICAL: Validate context type matches agent capability
        if not isinstance(agent_context, VideoAgentContext) or agent_context.context_type != "video":
            raise ValueError(f"VideoAgent requires VideoAgentContext with context_type='video', got {type(agent_context)} with context_type='{getattr(agent_context, 'context_type', 'unknown')}'")
        
        # Validate required fields
        if not agent_context.transcript_delta:
            raise ValueError("VideoAgentContext requires non-empty transcript_delta field")
        
        # Add strictness context to prompt
        strictness_guidance = f"""STRICTNESS LEVEL: {strictness}
Confidence threshold: {STRICTNESS_THRESHOLDS.get(strictness, STRICTNESS_THRESHOLDS[0.5])['min_confidence']}
Apply appropriate fact-checking rigor considering platform context (entertainment vs news)."""
        
        user_prompt = f"""{strictness_guidance}

Agent context: {agent_context.model_dump_json(by_alias=True, indent=2)}

Please fact-check the video content. Use platform metadata to understand context and credibility. Be aware of potential rapid content changes in short-form videos.
Transcript delta: {agent_context.transcript_delta}"""
        
        result = await self._invoke_with_tools(self.SYSTEM_PROMPT, user_prompt)
        return self.apply_strictness_filter(result, strictness)


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
                # Get strictness from session memory
                strictness = memory.settings.strictness
                
                if manager_response.route == MediaType.TEXT:
                    fact_check_result = await self.text_agent.fact_check(manager_response.agent_context, strictness)
                elif manager_response.route == MediaType.TEXT_IMAGE:
                    fact_check_result = await self.text_image_agent.fact_check(manager_response.agent_context, strictness)
                elif manager_response.route in [MediaType.SHORT_VIDEO, MediaType.LONG_VIDEO]:
                    fact_check_result = await self.video_agent.fact_check(manager_response.agent_context, strictness)
                else:
                    fact_check_result = None
                
                # Step 3: Manager synthesizes final notifications based on agent results
                if fact_check_result:
                    # Create synthesis prompt for manager
                    synthesis_prompt = f"""Based on the fact-checking analysis, synthesize the final notification:

Original Manager Assessment:
{manager_response.model_dump_json(indent=2)}

Fact-Check Results:
{fact_check_result.model_dump_json(indent=2)}

Please provide a final notification that incorporates both the content analysis and fact-checking results.
Focus on actionable insights for the user."""

                    try:
                        synthesis_response = await self.bedrock.invoke_model(
                            model_id=settings.bedrock_agent_model,
                            system_prompt=self.manager.SYSTEM_PROMPT,
                            messages=[{
                                "role": "user",
                                "content": synthesis_prompt
                            }],
                            tools_config=None  # No tools needed for synthesis
                        )
                        
                        # Parse synthesis response and update notifications
                        if synthesis_response and "response" in synthesis_response:
                            synthesis_text = synthesis_response["response"]
                            
                            # Extract key information from synthesis
                            if "urgent" in synthesis_text.lower() or "false" in synthesis_text.lower():
                                # Upgrade notification priority
                                for notification in manager_response.notifications:
                                    if notification.color == NotificationColor.YELLOW:
                                        notification.color = NotificationColor.RED
                                        notification.shortText = f"⚠️ {notification.shortText}"
                            
                            # Add fact-check context to details
                            if manager_response.notifications:
                                first_notification = manager_response.notifications[0]
                                if fact_check_result.summary:
                                    first_notification.details = f"{first_notification.details}\n\nFact-check: {fact_check_result.summary}"
                                
                                # Add sources from fact-checking
                                if fact_check_result.sources:
                                    first_notification.sources.extend(fact_check_result.sources)
                        
                    except Exception as e:
                        print(f"Synthesis error: {e}")
                        # Continue with original manager response
                    
            except Exception as e:
                print(f"Media agent error: {e}")
                # Continue with manager response even if media agent fails
        
        return manager_response


# Global orchestrator instance
orchestrator = AgentOrchestrator()
