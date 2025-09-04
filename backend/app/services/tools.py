"""Tool functions for LLM agents to interact with external APIs and services."""

import asyncio
import json
import re
from typing import List, Dict, Any, Optional
from urllib.parse import urlparse, parse_qs
import aiohttp
import boto3
from googleapiclient.discovery import build
from app.core.config import settings
from app.models.schemas import WebSearchResult, ReverseImageSearchResult, YouTubeMetadata


class ToolError(Exception):
    """Custom exception for tool function errors."""
    pass


class WebSearchTool:
    """Web search functionality using multiple search providers."""
    
    def __init__(self):
        self.bing_headers = {
            'Ocp-Apim-Subscription-Key': settings.bing_search_api_key,
            'User-Agent': 'Checkmate/1.0'
        }
    
    async def search(self, query: str, max_results: int = 5) -> List[WebSearchResult]:
        """Perform web search using Bing Search API."""
        if not settings.bing_search_api_key:
            raise ToolError("Bing Search API key not configured")
        
        params = {
            'q': query,
            'count': max_results,
            'responseFilter': 'WebPages',
            'textFormat': 'HTML'
        }
        
        async with aiohttp.ClientSession() as session:
            async with session.get(
                settings.bing_search_endpoint,
                headers=self.bing_headers,
                params=params
            ) as response:
                if response.status != 200:
                    raise ToolError(f"Search API returned status {response.status}")
                
                data = await response.json()
                results = []
                
                for item in data.get('webPages', {}).get('value', []):
                    results.append(WebSearchResult(
                        title=item.get('name', ''),
                        url=item.get('url', ''),
                        snippet=item.get('snippet', ''),
                        published_date=item.get('datePublished')
                    ))
                
                return results


class URLFetchTool:
    """Tool for fetching content from URLs."""
    
    async def fetch_url(self, url: str, max_chars: int = 5000) -> str:
        """Fetch content from a URL and return text content."""
        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36'
        }
        
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(url, headers=headers, timeout=10) as response:
                    if response.status != 200:
                        return f"Error: HTTP {response.status}"
                    
                    content = await response.text()
                    
                    # Simple text extraction (in production, use BeautifulSoup or similar)
                    # Remove HTML tags
                    import re
                    text = re.sub(r'<[^>]+>', ' ', content)
                    # Clean up whitespace
                    text = re.sub(r'\s+', ' ', text).strip()
                    
                    return text[:max_chars]
        
        except asyncio.TimeoutError:
            return "Error: Request timeout"
        except Exception as e:
            return f"Error: {str(e)}"


class ReverseImageSearchTool:
    """Tool for reverse image search using Google Custom Search."""
    
    def __init__(self):
        self.service = None
        if settings.google_custom_search_api_key and settings.google_custom_search_engine_id:
            self.service = build("customsearch", "v1", developerKey=settings.google_custom_search_api_key)
    
    async def reverse_search(self, image_url: str) -> ReverseImageSearchResult:
        """Perform reverse image search."""
        if not self.service:
            # Return empty results if not configured
            return ReverseImageSearchResult(
                similar_images=[],
                best_guess="Google Custom Search not configured",
                matching_pages=[]
            )
        
        try:
            # Perform reverse image search using the image URL
            result = self.service.cse().list(
                q=f"",  # Empty query for image search
                cx=settings.google_custom_search_engine_id,
                searchType="image",
                imgType="photo",
                imgSize="large",
                num=10,
                exactTerms=image_url,  # Use the provided image URL
                # Alternative: use imgUrl parameter if supported
                # imgUrl=image_url
            ).execute()
            
            similar_images = []
            matching_pages = []
            best_guess = None
            
            # Extract similar images and contextual information
            for item in result.get('items', []):
                image_link = item.get('link', '')
                if image_link and image_link not in similar_images:
                    similar_images.append(image_link)
                
                # Extract best guess from snippet or title
                if not best_guess and 'snippet' in item:
                    snippet = item['snippet'].lower()
                    if any(keyword in snippet for keyword in ['shows', 'depicts', 'contains', 'features']):
                        best_guess = item['snippet'][:100]
                
                # Try to find pages that reference this image
                if 'image' in item:
                    context_link = item['image'].get('contextLink')
                    if context_link:
                        matching_pages.append({
                            'title': item.get('title', '')[:100],
                            'url': context_link,
                            'snippet': item.get('snippet', '')[:200]
                        })
            
            return ReverseImageSearchResult(
                similar_images=similar_images[:5],
                best_guess=best_guess or "No clear identification found",
                matching_pages=matching_pages[:3]
            )
        
        except Exception as e:
            print(f"Reverse image search error: {e}")
            # Return partial results rather than failing completely
            return ReverseImageSearchResult(
                similar_images=[],
                best_guess=f"Search failed: {str(e)[:50]}...",
                matching_pages=[]
            )


class YouTubeMetadataTool:
    """Tool for fetching YouTube video and channel metadata."""
    
    def __init__(self):
        self.service = None
        if settings.youtube_api_key:
            self.service = build("youtube", "v3", developerKey=settings.youtube_api_key)
    
    def extract_video_id(self, url_or_guess: str) -> Optional[str]:
        """Extract YouTube video ID from URL or guess."""
        patterns = [
            r'(?:youtube\.com/watch\?v=|youtu\.be/|youtube\.com/embed/)([a-zA-Z0-9_-]{11})',
            r'youtube\.com/.*[?&]v=([a-zA-Z0-9_-]{11})',
        ]
        
        for pattern in patterns:
            match = re.search(pattern, url_or_guess)
            if match:
                return match.group(1)
        
        # If it looks like a video ID itself
        if re.match(r'^[a-zA-Z0-9_-]{11}$', url_or_guess):
            return url_or_guess
        
        return None
    
    async def get_metadata(self, url_or_guess: str) -> Optional[YouTubeMetadata]:
        """Get YouTube video metadata."""
        if not self.service:
            raise ToolError("YouTube API not configured")
        
        video_id = self.extract_video_id(url_or_guess)
        if not video_id:
            return None
        
        try:
            # Get video details
            video_response = self.service.videos().list(
                part="snippet,statistics",
                id=video_id
            ).execute()
            
            if not video_response.get('items'):
                return None
            
            video = video_response['items'][0]
            snippet = video['snippet']
            stats = video.get('statistics', {})
            
            # Get channel details
            channel_response = self.service.channels().list(
                part="snippet",
                id=snippet['channelId']
            ).execute()
            
            channel_name = snippet['channelTitle']
            if channel_response.get('items'):
                channel_name = channel_response['items'][0]['snippet']['title']
            
            return YouTubeMetadata(
                title=snippet.get('title', ''),
                channel_name=channel_name,
                channel_id=snippet.get('channelId', ''),
                description=snippet.get('description', ''),
                tags=snippet.get('tags', []),
                category=snippet.get('categoryId'),
                published_at=snippet.get('publishedAt'),
                view_count=int(stats.get('viewCount', 0)),
                like_count=int(stats.get('likeCount', 0))
            )
        
        except Exception as e:
            raise ToolError(f"YouTube metadata fetch failed: {str(e)}")


class TikTokMetadataTool:
    """Tool for extracting TikTok metadata (limited without official API)."""
    
    async def get_metadata(self, url_or_guess: str) -> Dict[str, Any]:
        """Get basic TikTok metadata from URL structure."""
        # Extract username and video ID from TikTok URL
        patterns = [
            r'tiktok\.com/@([^/]+)/video/(\d+)',
            r'tiktok\.com/.*t/(\w+)',
        ]
        
        for pattern in patterns:
            match = re.search(pattern, url_or_guess)
            if match:
                if len(match.groups()) == 2:
                    username, video_id = match.groups()
                    return {
                        "platform": "tiktok",
                        "username": username,
                        "video_id": video_id,
                        "is_short_form": True
                    }
                else:
                    return {
                        "platform": "tiktok",
                        "video_id": match.group(1),
                        "is_short_form": True
                    }
        
        return {"platform": "unknown"}


class ClaimCheckTool:
    """Tool for checking claims against fact-checking databases."""
    
    async def check_claim(self, statement: str) -> Dict[str, Any]:
        """Check a claim against known fact-checking sources."""
        # This would integrate with fact-checking APIs like:
        # - FactCheck.org
        # - Snopes API
        # - PolitiFact API
        # For now, return a placeholder structure
        
        search_tool = WebSearchTool()
        
        # Search for fact-checks of this claim
        fact_check_queries = [
            f"{statement} fact check",
            f"{statement} snopes",
            f"{statement} factcheck.org",
            f'"{statement}" debunked'
        ]
        
        all_results = []
        for query in fact_check_queries:
            try:
                results = await search_tool.search(query, max_results=3)
                all_results.extend(results)
            except:
                continue
        
        # Filter for known fact-checking domains
        fact_check_domains = [
            'snopes.com', 'factcheck.org', 'politifact.com',
            'reuters.com', 'ap.org', 'bbc.com'
        ]
        
        relevant_results = []
        for result in all_results:
            domain = urlparse(result.url).netloc.lower()
            if any(fc_domain in domain for fc_domain in fact_check_domains):
                relevant_results.append(result)
        
        return {
            "claim": statement,
            "fact_check_results": relevant_results[:5],
            "confidence": 0.7 if relevant_results else 0.3
        }


class ToolRegistry:
    """Registry for all available tools."""
    
    def __init__(self):
        self.web_search = WebSearchTool()
        self.url_fetch = URLFetchTool()
        self.reverse_image_search = ReverseImageSearchTool()
        self.youtube_meta = YouTubeMetadataTool()
        self.tiktok_meta = TikTokMetadataTool()
        self.claim_check = ClaimCheckTool()
    
    def get_tool_descriptions(self) -> List[Dict[str, Any]]:
        """Get function descriptions for LLM tool calling."""
        return [
            {
                "name": "web_search",
                "description": "Search the web for information about a topic or claim",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "description": "Search query"
                        },
                        "max_results": {
                            "type": "integer",
                            "description": "Maximum number of results to return",
                            "default": 5
                        }
                    },
                    "required": ["query"]
                }
            },
            {
                "name": "fetch_url",
                "description": "Fetch content from a specific URL",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "url": {
                            "type": "string",
                            "description": "URL to fetch content from"
                        },
                        "max_chars": {
                            "type": "integer",
                            "description": "Maximum characters to return",
                            "default": 5000
                        }
                    },
                    "required": ["url"]
                }
            },
            {
                "name": "reverse_image_search",
                "description": "Perform reverse image search to find similar images and their sources",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "image_ref": {
                            "type": "string",
                            "description": "Reference to the image (S3 URL or path)"
                        }
                    },
                    "required": ["image_ref"]
                }
            },
            {
                "name": "yt_meta",
                "description": "Get YouTube video and channel metadata",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "url_or_guess": {
                            "type": "string",
                            "description": "YouTube URL or video ID"
                        }
                    },
                    "required": ["url_or_guess"]
                }
            },
            {
                "name": "tiktok_meta",
                "description": "Get TikTok video metadata",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "url_or_guess": {
                            "type": "string",
                            "description": "TikTok URL or video identifier"
                        }
                    },
                    "required": ["url_or_guess"]
                }
            },
            {
                "name": "claim_check",
                "description": "Check a factual claim against fact-checking databases",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "statement": {
                            "type": "string",
                            "description": "The claim or statement to fact-check"
                        }
                    },
                    "required": ["statement"]
                }
            }
        ]
    
    async def call_tool(self, tool_name: str, **kwargs) -> Any:
        """Call a tool function by name."""
        if tool_name == "web_search":
            return await self.web_search.search(**kwargs)
        elif tool_name == "fetch_url":
            return await self.url_fetch.fetch_url(**kwargs)
        elif tool_name == "reverse_image_search":
            return await self.reverse_image_search.reverse_search(**kwargs)
        elif tool_name == "yt_meta":
            return await self.youtube_meta.get_metadata(**kwargs)
        elif tool_name == "tiktok_meta":
            return await self.tiktok_meta.get_metadata(**kwargs)
        elif tool_name == "claim_check":
            return await self.claim_check.check_claim(**kwargs)
        else:
            raise ToolError(f"Unknown tool: {tool_name}")


# Global tool registry instance
tools = ToolRegistry()
