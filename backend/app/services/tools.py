"""Tool functions for LLM agents to interact with external APIs and services."""

import asyncio
import json
import re
from typing import List, Dict, Any, Optional
from urllib.parse import urlparse, parse_qs

try:
    import aiohttp
except ImportError:
    aiohttp = None

try:
    import boto3
except ImportError:
    boto3 = None

try:
    from googleapiclient.discovery import build
except ImportError:
    build = None

from app.core.config import settings
from app.models.schemas import WebSearchResult, ReverseImageSearchResult, YouTubeMetadata


class ToolError(Exception):
    """Custom exception for tool function errors."""
    pass


class WebSearchTool:
    """Web search functionality using multiple search providers (SerpAPI preferred, Bing fallback)."""

    def __init__(self):
        # --- Provider config
        self.serpapi_key = getattr(settings, "serpapi_api_key", None)
        self.serpapi_endpoint = getattr(settings, "serpapi_endpoint", "https://serpapi.com/search.json")

        self.bing_key = getattr(settings, "bing_search_api_key", None) or "dummy"
        self.bing_endpoint = getattr(settings, "bing_search_endpoint", "https://api.bing.microsoft.com/v7.0/search")

        # Keep original header + flag names for backward compatibility
        self.bing_headers = {
            "Ocp-Apim-Subscription-Key": self.bing_key,
            "User-Agent": "Checkmate/1.0",
        }

        # is_configured stays true if any provider is available
        self.is_configured = bool(self.serpapi_key or (getattr(settings, "bing_search_api_key", None)))

        # Rate limiting controls (shared across providers)
        self.rate_limit_delay = 1.0  # seconds between requests
        self.last_request_time = 0

    async def validate_api_key(self) -> bool:
        """Validate configured provider key by making a tiny test request.

        Returns:
            True if a valid key appears to be configured (or the provider returns 429 rate limited),
            False otherwise.
        """
        if not self.is_configured or not aiohttp:
            return False

        # Prefer SerpAPI if present; otherwise try Bing.
        if self.serpapi_key:
            try:
                params = {
                    "engine": "google",
                    "q": "test",
                    "api_key": self.serpapi_key,
                    "num": 1,
                }
                async with aiohttp.ClientSession() as session:
                    async with session.get(self.serpapi_endpoint, params=params, timeout=5) as resp:
                        # SerpAPI typically returns 200 even for errors; check payload
                        if resp.status not in (200, 429):
                            return False
                        data = await self._safe_json(resp)
                        # Success path: search_metadata.status == "Success" and no "error"
                        status_ok = (
                            isinstance(data, dict)
                            and data.get("search_metadata", {}).get("status") == "Success"
                            and "error" not in data
                        )
                        rate_limited = resp.status == 429 or (
                            isinstance(data, dict) and "error" in data and "Rate limit" in str(data["error"])
                        )
                        return bool(status_ok or rate_limited)
            except Exception:
                return False

        # Bing fallback validation
        if getattr(settings, "bing_search_api_key", None):
            try:
                params = {"q": "test", "count": 1}
                async with aiohttp.ClientSession() as session:
                    async with session.get(
                        self.bing_endpoint, headers=self.bing_headers, params=params, timeout=5
                    ) as response:
                        return response.status in (200, 429)
            except Exception:
                return False

        return False

    async def _apply_rate_limiting(self):
        """Apply rate limiting between requests."""
        import time
        current_time = time.time()
        elapsed = current_time - self.last_request_time
        if elapsed < self.rate_limit_delay:
            await asyncio.sleep(self.rate_limit_delay - elapsed)
        self.last_request_time = time.time()

    async def search(self, query: str, max_results: int = 5) -> List["WebSearchResult"]:
        """Perform web search with enhanced error handling.

        Args:
            query: Search query string.
            max_results: Desired number of results (capped per provider limits).

        Returns:
            List[WebSearchResult]
        """
        # Development / testing fallback (no provider configured or aiohttp unavailable)
        if (not self.is_configured) or (not aiohttp):
            return [
                WebSearchResult(
                    title=f"[MOCK] Search result for: {query}",
                    url="https://example.com/search-not-available",
                    snippet=f"This is a mock search result for query: {query}. Web search API not configured.",
                    published_date=None,
                )
            ]

        await self._apply_rate_limiting()

        # Prefer SerpAPI if available
        if self.serpapi_key:
            try:
                results = await self._search_serpapi(query=query, max_results=max_results)
                if results:
                    return results
                # If SerpAPI returned nothing usable but Bing is configured, try fallback
                if getattr(settings, "bing_search_api_key", None):
                    return await self._search_bing(query=query, max_results=max_results)
                # Last-resort mock if nothing returned
                return self._mock_result(query)
            except Exception as e:
                # On SerpAPI error, attempt Bing if present
                if getattr(settings, "bing_search_api_key", None):
                    try:
                        return await self._search_bing(query=query, max_results=max_results)
                    except Exception as be:
                        raise ToolError(f"Web search failed (SerpAPI -> Bing): {e} | {be}")
                raise ToolError(f"Web search failed (SerpAPI): {e}")

        # If only Bing is available
        try:
            return await self._search_bing(query=query, max_results=max_results)
        except Exception as e:
            raise ToolError(f"Web search failed (Bing): {e}")

    # ------------------------
    # Internal provider methods
    # ------------------------

    async def _search_serpapi(self, query: str, max_results: int) -> List["WebSearchResult"]:
        """Search via SerpAPI (Google engine)."""
        params = {
            "engine": "google",
            "q": query,
            "api_key": self.serpapi_key,
            "num": min(max_results, 10),  # SerpAPI 'num' behaves like per-page size
            # You can add localization/safelook settings if desired:
            # "hl": "en", "gl": "us", "safe": "active"
        }

        async with aiohttp.ClientSession() as session:
            async with session.get(self.serpapi_endpoint, params=params, timeout=10) as response:
                # SerpAPI frequently returns 200; errors live in JSON payload.
                if response.status == 429:
                    # Back off briefly and retry once
                    await asyncio.sleep(2)
                    async with session.get(self.serpapi_endpoint, params=params, timeout=10) as retry_resp:
                        return await self._parse_serpapi_response(retry_resp, max_results)
                return await self._parse_serpapi_response(response, max_results)

    async def _parse_serpapi_response(self, resp, max_results: int) -> List["WebSearchResult"]:
        data = await self._safe_json(resp)

        if not isinstance(data, dict):
            raise ToolError(f"SerpAPI: Unexpected response type (status={resp.status})")

        # Detect SerpAPI error formats
        if "error" in data:
            # Common messages: "Rate limit reached", "Invalid API key", etc.
            raise ToolError(f"SerpAPI error: {data['error']} (status={resp.status})")

        meta_status = data.get("search_metadata", {}).get("status")
        if meta_status not in ("Success", "Cached"):
            # Some responses might still include results; we’ll proceed but warn
            pass

        # Primary: standard organic results
        organic = data.get("organic_results", []) or []
        # Optional: also consider "news_results" as fallback if organic empty
        news = data.get("news_results", []) or []

        items = []
        for item in organic:
            url = item.get("link", "") or ""
            result = WebSearchResult(
                title=item.get("title", "") or "",
                url=url,
                snippet=item.get("snippet", "") or "",
                # SerpAPI sometimes has 'date' in organic results, but not guaranteed
                published_date=item.get("date"),
            )
            result.tier = self._classify_source_tier(url)
            items.append(result)
            if len(items) >= max_results:
                break

        # If organic insufficient, top-up with news results (mapped to same schema)
        if len(items) < max_results and news:
            for n in news:
                url = n.get("link", "") or ""
                result = WebSearchResult(
                    title=n.get("title", "") or "",
                    url=url,
                    snippet=n.get("snippet", "") or n.get("source", "") or "",
                    published_date=n.get("date"),
                )
                result.tier = self._classify_source_tier(url)
                items.append(result)
                if len(items) >= max_results:
                    break

        return items

    async def _search_bing(self, query: str, max_results: int) -> List["WebSearchResult"]:
        """Search via Bing Web Search API."""
        params = {
            "q": query,
            "count": min(max_results, 10),  # Bing limit
            "responseFilter": "WebPages",
            "textFormat": "HTML",
            "safeSearch": "Moderate",
        }

        async with aiohttp.ClientSession() as session:
            async with session.get(self.bing_endpoint, headers=self.bing_headers, params=params, timeout=10) as response:
                if response.status == 429:
                    # Rate limited - wait and retry once
                    await asyncio.sleep(2)
                    async with session.get(
                        self.bing_endpoint, headers=self.bing_headers, params=params, timeout=10
                    ) as retry_response:
                        if retry_response.status != 200:
                            raise ToolError(f"Search API rate limited: {retry_response.status}")
                        return await self._parse_bing_response(retry_response, max_results)
                elif response.status != 200:
                    raise ToolError(f"Search API returned status {response.status}")

                return await self._parse_bing_response(response, max_results)

    async def _parse_bing_response(self, resp, max_results: int) -> List["WebSearchResult"]:
        data = await self._safe_json(resp)
        if not isinstance(data, dict):
            raise ToolError(f"Bing: Unexpected response type (status={resp.status})")

        values = data.get("webPages", {}).get("value", []) or []
        results = []
        for item in values[:max_results]:
            url = item.get("url", "") or ""
            r = WebSearchResult(
                title=item.get("name", "") or "",
                url=url,
                snippet=item.get("snippet", "") or "",
                published_date=item.get("datePublished"),
            )
            r.tier = self._classify_source_tier(url)
            results.append(r)
        return results

    # ------------------------
    # Helpers
    # ------------------------

    def _mock_result(self, query: str) -> List["WebSearchResult"]:
        return [
            WebSearchResult(
                title=f"[MOCK] Search result for: {query}",
                url="https://example.com/search-not-available",
                snippet=f"This is a mock search result for query: {query}. Web search API not configured.",
                published_date=None,
            )
        ]

    async def _safe_json(self, resp):
        """Best-effort JSON parsing with graceful fallback."""
        try:
            return await resp.json(content_type=None)
        except Exception:
            try:
                text = await resp.text()
                # Attempt a late parse if response mislabels content-type
                import json
                return json.loads(text)
            except Exception:
                return None

    def _classify_source_tier(self, url: str) -> str:
        """Classify source tier based on URL domain."""
        from app.core.config import SOURCE_TIERS
        domain = url.lower()

        for tier_a_domain in SOURCE_TIERS.get("A", []):
            if tier_a_domain in domain:
                return "A"

        for tier_b_domain in SOURCE_TIERS.get("B", []):
            if tier_b_domain in domain:
                return "B"

        for tier_c_domain in SOURCE_TIERS.get("C", []):
            if tier_c_domain in domain:
                return "C"

        # Default to tier B for unknown but established domains
        return "B"



class URLFetchTool:
    """Tool for fetching content from URLs."""
    
    async def fetch_url(self, url: str, max_chars: int = 5000) -> str:
        """Fetch content from a URL and return text content."""
        if not aiohttp:
            return f"URL fetch not available (aiohttp not installed): {url}"
        
        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
        }
        
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(url, headers=headers, timeout=10) as response:
                    if response.status != 200:
                        return f"Error: HTTP {response.status}"
                    
                    content = await response.text()
                    
                    # Simple text extraction
                    text = re.sub(r'<[^>]+>', ' ', content)
                    text = re.sub(r'\s+', ' ', text).strip()
                    
                    return text[:max_chars]
        
        except asyncio.TimeoutError:
            return "Error: Request timeout"
        except Exception as e:
            return f"Error: {str(e)}"


class ReverseImageSearchTool:
    """Tool for reverse image search using SERPAPI with Google reverse image search engine."""
    
    def __init__(self):
        # SERPAPI configuration (primary)
        self.serpapi_key = getattr(settings, "serpapi_api_key", None)
        self.serpapi_endpoint = "https://serpapi.com/search.json"
        
        # Legacy Google Custom Search (fallback)
        self.service = None
        self.google_configured = bool(build and settings.google_custom_search_api_key and settings.google_custom_search_engine_id)
        
        # Primary configuration check - SERPAPI preferred
        self.is_configured = bool(self.serpapi_key or self.google_configured)
        
        # Rate limiting
        self.rate_limit_delay = 2.0  # More conservative for image search
        self.last_request_time = 0
        
        # Setup legacy Google Custom Search as fallback
        if self.google_configured:
            try:
                self.service = build("customsearch", "v1", developerKey=settings.google_custom_search_api_key)
            except Exception:
                self.service = None
                self.google_configured = False
    
    async def validate_api_key(self) -> bool:
        """Validate SERPAPI configuration with test reverse image search."""
        if not self.is_configured:
            return False
            
        # Prefer SERPAPI validation
        if self.serpapi_key and aiohttp:
            try:
                # Test with a simple image URL
                params = {
                    "engine": "google_reverse_image",
                    "api_key": self.serpapi_key,
                    "image_url": "https://upload.wikimedia.org/wikipedia/commons/thumb/4/41/Sunflower_from_Silesia2.jpg/256px-Sunflower_from_Silesia2.jpg",
                    "num": 1
                }
                async with aiohttp.ClientSession() as session:
                    async with session.get(self.serpapi_endpoint, params=params, timeout=10) as resp:
                        if resp.status not in (200, 429):
                            return False
                        data = await self._safe_json(resp)
                        # Check for successful SERPAPI response
                        return (
                            isinstance(data, dict) and
                            data.get("search_metadata", {}).get("status") == "Success" and
                            "error" not in data
                        ) or resp.status == 429  # Rate limited but key valid
            except Exception:
                pass
        
        # Fallback to Google Custom Search validation
        if self.google_configured and self.service:
            try:
                result = self.service.cse().list(
                    q="test",
                    cx=settings.google_custom_search_engine_id,
                    num=1
                ).execute()
                return True
            except Exception:
                pass
                
        return False
    
    async def _safe_json(self, resp):
        """Safe JSON parsing with fallback."""
        try:
            return await resp.json(content_type=None)
        except Exception:
            try:
                text = await resp.text()
                import json
                return json.loads(text)
            except Exception:
                return None
    
    async def _apply_rate_limiting(self):
        """Apply rate limiting between requests."""
        import time
        current_time = time.time()
        time_since_last = current_time - self.last_request_time
        if time_since_last < self.rate_limit_delay:
            await asyncio.sleep(self.rate_limit_delay - time_since_last)
        self.last_request_time = time.time()
    
    def _classify_source_tier(self, url: str) -> str:
        """Classify source tier for reverse image search results."""
        if not url:
            return "C"
            
        domain = url.lower()
        
        # Professional photo agencies and news sources (Tier A)
        tier_a_indicators = [
            'getty', 'shutterstock', 'ap.org', 'reuters', 'afp.com',
            'bbc.com', 'cnn.com', 'nytimes.com', 'washingtonpost.com',
            'theguardian.com', 'npr.org', 'pbs.org'
        ]
        
        for indicator in tier_a_indicators:
            if indicator in domain:
                return "A"
        
        # Established media and platforms (Tier B)
        tier_b_indicators = [
            'wikipedia.org', 'wikimedia.org', 'britannica.com',
            'nationalgeographic.com', 'smithsonianmag.com',
            'time.com', 'newsweek.com', 'economist.com'
        ]
        
        for indicator in tier_b_indicators:
            if indicator in domain:
                return "B"
                
        # Social media and user-generated content (Tier C)
        tier_c_indicators = [
            'twitter.com', 'facebook.com', 'instagram.com', 'tiktok.com',
            'reddit.com', 'pinterest.com', 'flickr.com', 'tumblr.com'
        ]
        
        for indicator in tier_c_indicators:
            if indicator in domain:
                return "C"
                
        # Default to tier B for unknown but established domains
        return "B"
    
    async def _reverse_search_serpapi(self, image_url: str) -> ReverseImageSearchResult:
        """Perform reverse image search using SERPAPI."""
        params = {
            "engine": "google_reverse_image",
            "api_key": self.serpapi_key,
            "image_url": image_url,
            "num": 20,  # Get more results for better analysis
            "safe": "active"
        }
        
        async with aiohttp.ClientSession() as session:
            async with session.get(self.serpapi_endpoint, params=params, timeout=20) as resp:
                if resp.status == 429:
                    # Rate limited - wait and retry once
                    await asyncio.sleep(5)
                    async with session.get(self.serpapi_endpoint, params=params, timeout=20) as retry_resp:
                        if retry_resp.status != 200:
                            raise ToolError(f"SERPAPI rate limited: {retry_resp.status}")
                        resp = retry_resp
                elif resp.status != 200:
                    raise ToolError(f"SERPAPI returned status {resp.status}")
                
                data = await self._safe_json(resp)
                if not data:
                    raise ToolError("Invalid SERPAPI response")
                
                # Check for SERPAPI errors
                if "error" in data:
                    error_msg = data["error"]
                    # If it's just "no results", return empty result instead of failing
                    if "hasn't returned any results" in error_msg:
                        return ReverseImageSearchResult(
                            similar_images=[],
                            best_guess="No reverse image search results found for this image",
                            matching_pages=[]
                        )
                    else:
                        raise ToolError(f"SERPAPI error: {error_msg}")
                
                return self._parse_serpapi_response(data)
    
    def _parse_serpapi_response(self, data: dict) -> ReverseImageSearchResult:
        """Parse SERPAPI reverse image search response."""
        similar_images = []
        matching_pages = []
        best_guess = None
        
        # Extract best guess from search metadata
        search_metadata = data.get("search_metadata", {})
        if search_metadata.get("google_lens_suggestion"):
            best_guess = search_metadata["google_lens_suggestion"]
        
        # Process image results
        image_results = data.get("image_results", [])
        for item in image_results[:10]:  # Limit to first 10 image results
            if item.get("original"):
                similar_images.append(item["original"])
        
        # Process inline images (more detailed results)
        inline_images = data.get("inline_images", [])
        for item in inline_images[:5]:  # Limit to first 5 detailed results
            if item.get("original"):
                similar_images.append(item["original"])
            
            # Extract context page information
            if item.get("link") and item.get("title"):
                tier = self._classify_source_tier(item["link"])
                
                page_result = WebSearchResult(
                    title=item.get("title", "")[:100],
                    url=item["link"],
                    snippet=item.get("snippet", "")[:200],
                    tier=tier
                )
                matching_pages.append(page_result)
        
        # Process knowledge graph results for best guess
        knowledge_graph = data.get("knowledge_graph", {})
        if not best_guess and knowledge_graph.get("title"):
            best_guess = knowledge_graph["title"]
        
        # Process search results for additional context pages
        organic_results = data.get("organic_results", [])
        for item in organic_results[:3]:  # Add top 3 organic results
            if item.get("link") and item.get("title"):
                tier = self._classify_source_tier(item["link"])
                
                page_result = WebSearchResult(
                    title=item.get("title", "")[:100],
                    url=item["link"],
                    snippet=item.get("snippet", "")[:200],
                    tier=tier
                )
                matching_pages.append(page_result)
        
        # Remove duplicate images and sort by relevance
        similar_images = list(dict.fromkeys(similar_images))  # Remove duplicates while preserving order
        
        # Sort matching pages by tier quality (A > B > C)
        tier_order = {"A": 0, "B": 1, "C": 2}
        matching_pages.sort(key=lambda x: tier_order.get(x.tier, 1))
        
        return ReverseImageSearchResult(
            similar_images=similar_images[:5],  # Return top 5 similar images
            best_guess=best_guess or "No clear identification found",
            matching_pages=matching_pages[:5]  # Return top 5 context pages
        )
    
    async def _reverse_search_google_fallback(self, image_url: str) -> ReverseImageSearchResult:
        """Fallback reverse image search using Google Custom Search API."""
        result = self.service.cse().list(
            q=f"site:* {image_url}",  # Search for the image URL instead of using imgUrl
            cx=settings.google_custom_search_engine_id,
            searchType="image",
            num=10,
            safe="active"
        ).execute()
        
        similar_images = []
        matching_pages = []
        best_guess = None
        
        for item in result.get('items', []):
            image_link = item.get('link', '')
            if image_link:
                similar_images.append(image_link)
            
            if not best_guess and 'snippet' in item:
                best_guess = item['snippet'][:150]
            
            if 'image' in item:
                context_link = item['image'].get('contextLink')
                if context_link:
                    tier = self._classify_source_tier(context_link)
                    
                    page_result = WebSearchResult(
                        title=item.get('title', '')[:100],
                        url=context_link,
                        snippet=item.get('snippet', '')[:200],
                        tier=tier
                    )
                    matching_pages.append(page_result)
        
        # Sort by tier quality
        tier_order = {"A": 0, "B": 1, "C": 2}
        matching_pages.sort(key=lambda x: tier_order.get(x.tier, 1))
        
        return ReverseImageSearchResult(
            similar_images=similar_images[:5],
            best_guess=best_guess or "No clear identification found",
            matching_pages=matching_pages[:5]
        )
    
    async def reverse_search(self, image_url: str) -> ReverseImageSearchResult:
        """Perform reverse image search with SERPAPI primary and Google Custom Search fallback.
        
        Maintains exact same interface: input (image_url: str) -> output (ReverseImageSearchResult)
        """
        if not self.is_configured:
            return ReverseImageSearchResult(
                similar_images=[],
                best_guess="[MOCK] Reverse image search not configured - requires SERPAPI or Google Custom Search API",
                matching_pages=[]
            )
        
        await self._apply_rate_limiting()
        
        # Try SERPAPI first (preferred)
        if self.serpapi_key and aiohttp:
            try:
                return await self._reverse_search_serpapi(image_url)
            except Exception as e:
                # Log SERPAPI failure and try Google fallback
                pass
        
        # Fallback to Google Custom Search
        if self.google_configured and self.service:
            try:
                return await self._reverse_search_google_fallback(image_url)
            except Exception as e:
                return ReverseImageSearchResult(
                    similar_images=[],
                    best_guess=f"Reverse image search failed: {str(e)}",
                    matching_pages=[]
                )
        
        # Final fallback - return mock result
        return ReverseImageSearchResult(
            similar_images=[],
            best_guess="Reverse image search temporarily unavailable",
            matching_pages=[]
        )


class YouTubeMetadataTool:
    """Tool for fetching YouTube video and channel metadata."""
    
    def __init__(self):
        self.service = None
        self.is_configured = bool(build and settings.youtube_api_key)
        self.rate_limit_delay = 0.5  # YouTube has higher rate limits
        self.last_request_time = 0
        
        if self.is_configured:
            try:
                self.service = build("youtube", "v3", developerKey=settings.youtube_api_key)
            except Exception:
                self.service = None
                self.is_configured = False
    
    async def validate_api_key(self) -> bool:
        """Validate YouTube Data API key."""
        if not self.is_configured or not self.service:
            return False
        
        try:
            # Test with a simple query
            response = self.service.videos().list(
                part="snippet",
                id="dQw4w9WgXcQ",  # Rick Roll video ID - always exists
                maxResults=1
            ).execute()
            return True
        except Exception:
            return False
    
    async def _apply_rate_limiting(self):
        """Apply rate limiting between requests."""
        import time
        current_time = time.time()
        time_since_last = current_time - self.last_request_time
        if time_since_last < self.rate_limit_delay:
            await asyncio.sleep(self.rate_limit_delay - time_since_last)
        self.last_request_time = time.time()
    
    def extract_video_id(self, url_or_guess: str) -> Optional[str]:
        """Extract YouTube video ID from URL or guess with enhanced patterns."""
        patterns = [
            r'(?:youtube\.com/watch\?v=|youtu\.be/|youtube\.com/embed/)([a-zA-Z0-9_-]{11})',
            r'youtube\.com/.*[?&]v=([a-zA-Z0-9_-]{11})',
            r'youtube\.com/shorts/([a-zA-Z0-9_-]{11})',  # YouTube Shorts
            r'm\.youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})',  # Mobile YouTube
        ]
        
        for pattern in patterns:
            match = re.search(pattern, url_or_guess)
            if match:
                return match.group(1)
        
        # Direct video ID
        if re.match(r'^[a-zA-Z0-9_-]{11}$', url_or_guess):
            return url_or_guess
        
        return None
    
    def _classify_content_tier(self, channel_data: dict, video_data: dict) -> str:
        """Classify content tier based on channel and video characteristics."""
        snippet = video_data.get("snippet", {})
        statistics = video_data.get("statistics", {})
        channel_snippet = channel_data.get("snippet", {}) if channel_data else {}
        channel_stats = channel_data.get("statistics", {}) if channel_data else {}
        
        # Check for news/official channels
        channel_title = channel_snippet.get("title", "").lower()
        description = snippet.get("description", "").lower()
        
        # Tier A: Official news channels, verified sources
        if any(keyword in channel_title for keyword in [
            'bbc', 'cnn', 'reuters', 'ap news', 'npr', 'pbs', 'abc news', 'cbs news',
            'nbc news', 'fox news', 'sky news', 'the guardian', 'new york times'
        ]):
            return "A"
        
        # High subscriber count with good engagement
        subscriber_count = int(channel_stats.get("subscriberCount", 0))
        view_count = int(statistics.get("viewCount", 0))
        
        if subscriber_count > 1000000 and view_count > 100000:  # 1M+ subs, 100K+ views
            return "B"
        elif subscriber_count > 100000:  # 100K+ subs
            return "B"
        else:
            return "C"  # Smaller channels
    
    async def get_metadata(self, url_or_guess: str) -> Optional[YouTubeMetadata]:
        """Get YouTube video metadata with enhanced error handling and classification."""
        if not self.is_configured or not self.service:
            return None
        
        video_id = self.extract_video_id(url_or_guess)
        if not video_id:
            return None
        
        await self._apply_rate_limiting()
        
        try:
            # Get video details
            video_response = self.service.videos().list(
                part="snippet,statistics,contentDetails",
                id=video_id,
                maxResults=1
            ).execute()
            
            if not video_response.get("items"):
                return None
            
            video = video_response["items"][0]
            snippet = video["snippet"]
            statistics = video.get("statistics", {})
            
            # Get channel details
            channel_data = None
            try:
                channel_response = self.service.channels().list(
                    part="snippet,statistics",
                    id=snippet["channelId"],
                    maxResults=1
                ).execute()
                if channel_response.get("items"):
                    channel_data = channel_response["items"][0]
            except Exception:
                pass  # Continue without channel data if it fails
            
            # Classify content tier
            tier = self._classify_content_tier(channel_data, video)
            
            metadata = YouTubeMetadata(
                title=snippet.get("title", ""),
                channelName=snippet.get("channelTitle", ""),
                channelId=snippet["channelId"],
                description=snippet.get("description", "")[:500],
                tags=snippet.get("tags", [])[:10],  # Limit tags
                category=snippet.get("categoryId"),
                publishedAt=snippet.get("publishedAt"),
                viewCount=int(statistics.get("viewCount", 0)),
                likeCount=int(statistics.get("likeCount", 0))
            )
            
            # Add tier classification
            metadata.tier = tier
            
            return metadata
            
        except Exception as e:
            raise ToolError(f"YouTube API error: {e}")


class TikTokMetadataTool:
    """Tool for fetching TikTok video metadata with enhanced parsing."""
    
    def __init__(self):
        self.rate_limit_delay = 2.0  # TikTok is more restrictive
        self.last_request_time = 0
    
    async def _apply_rate_limiting(self):
        """Apply rate limiting between requests."""
        import time
        current_time = time.time()
        time_since_last = current_time - self.last_request_time
        if time_since_last < self.rate_limit_delay:
            await asyncio.sleep(self.rate_limit_delay - time_since_last)
        self.last_request_time = time.time()
    
    def extract_video_info(self, url_or_guess: str) -> Optional[Dict[str, str]]:
        """Extract TikTok video information from URL patterns."""
        patterns = [
            r'tiktok\.com/@([^/]+)/video/(\d+)',
            r'tiktok\.com/t/([a-zA-Z0-9]+)',  # Short links
            r'vm\.tiktok\.com/([a-zA-Z0-9]+)',  # Mobile short links
        ]
        
        for pattern in patterns:
            match = re.search(pattern, url_or_guess)
            if match:
                if len(match.groups()) == 2:
                    return {"username": match.group(1), "video_id": match.group(2)}
                else:
                    return {"short_code": match.group(1)}
        
        return None
    
    async def get_metadata(self, url_or_guess: str) -> Optional[Dict[str, Any]]:
        """Get TikTok video metadata with enhanced parsing and classification."""
        await self._apply_rate_limiting()
        
        try:
            video_info = self.extract_video_info(url_or_guess)
            
            if "tiktok.com" in url_or_guess:
                if video_info:
                    result = {
                        "platform": "tiktok",
                        "url": url_or_guess,
                        "is_short_form": True,
                        "tier": "C",  # Default TikTok to tier C due to user-generated content
                        **video_info
                    }
                    
                    # Try to enhance with basic web scraping if aiohttp available
                    if aiohttp:
                        try:
                            headers = {
                                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
                            }
                            async with aiohttp.ClientSession() as session:
                                async with session.get(url_or_guess, headers=headers, timeout=10) as response:
                                    if response.status == 200:
                                        content = await response.text()
                                        
                                        # Extract basic metadata from HTML
                                        title_match = re.search(r'<title>([^<]+)</title>', content)
                                        if title_match:
                                            result["title"] = title_match.group(1)
                                        
                                        # Look for verified accounts (higher tier)
                                        if "verified" in content.lower() or "official" in content.lower():
                                            result["tier"] = "B"
                        except Exception:
                            pass  # Continue with basic info if scraping fails
                    
                    return result
            
            return {
                "platform": "tiktok", 
                "url": url_or_guess,
                "is_short_form": True,
                "tier": "C",
                "note": "Limited metadata without official TikTok API access"
            }
            
        except Exception as e:
            raise ToolError(f"TikTok metadata error: {e}")


class FactCheckTool:
    """Tool for fact-checking claims with advanced consensus logic and source validation."""
    
    def __init__(self):
        self.web_search = WebSearchTool()
        self.rate_limit_delay = 1.0
        self.last_request_time = 0
        
        # Define fact-checking source tiers
        self.tier_a_sources = [
            'snopes.com', 'factcheck.org', 'politifact.com', 'ap.org/factcheck',
            'reuters.com/fact-check', 'bbc.com/reality-check'
        ]
        self.tier_b_sources = [
            'fullfact.org', 'checkyourfact.com', 'leadstories.com',
            'factcheckni.org', 'afp.com/factcheck'
        ]
    
    async def validate_api_dependencies(self) -> bool:
        """Check if web search dependency is available."""
        return await self.web_search.validate_api_key()
    
    async def _apply_rate_limiting(self):
        """Apply rate limiting between requests."""
        import time
        current_time = time.time()
        time_since_last = current_time - self.last_request_time
        if time_since_last < self.rate_limit_delay:
            await asyncio.sleep(self.rate_limit_delay - time_since_last)
        self.last_request_time = time.time()
    
    def _classify_fact_check_source(self, url: str) -> tuple[str, float]:
        """Classify fact-checking source and return tier and base confidence."""
        url_lower = url.lower()
        
        for source in self.tier_a_sources:
            if source in url_lower:
                return "A", 0.95
        
        for source in self.tier_b_sources:
            if source in url_lower:
                return "B", 0.85
        
        # Check for general news fact-checking sections
        if any(pattern in url_lower for pattern in ['fact-check', 'factcheck', 'reality-check']):
            return "B", 0.75
        
        return "C", 0.60
    
    def _extract_claim_status(self, snippet: str, title: str) -> tuple[str, float]:
        """Extract claim status from snippet and title with confidence scoring."""
        text = (snippet + " " + title).lower()
        
        # Define status indicators with confidence weights
        false_indicators = ['false', 'debunked', 'misleading', 'incorrect', 'wrong', 'fabricated']
        true_indicators = ['true', 'verified', 'confirmed', 'accurate', 'correct']
        contested_indicators = ['partly', 'mixed', 'partially', 'mostly false', 'mostly true', 'needs context']
        uncertain_indicators = ['unclear', 'unverified', 'no evidence', 'unknown']
        
        # Count weighted indicators
        false_score = sum(2 if indicator in text else 0 for indicator in false_indicators)
        true_score = sum(2 if indicator in text else 0 for indicator in true_indicators)
        contested_score = sum(1.5 if indicator in text else 0 for indicator in contested_indicators)
        uncertain_score = sum(1 if indicator in text else 0 for indicator in uncertain_indicators)
        
        scores = {
            'false': false_score,
            'supported': true_score,
            'contested': contested_score,
            'uncertain': uncertain_score
        }
        
        if max(scores.values()) == 0:
            return "uncertain", 0.3
        
        status = max(scores.items(), key=lambda x: x[1])[0]
        confidence = min(max(scores[status] / 4.0, 0.4), 0.95)  # Normalize to 0.4-0.95 range
        
        return status, confidence
    
    async def check_claim(self, statement: str) -> Dict[str, Any]:
        """Check a claim with enhanced consensus logic and source validation."""
        await self._apply_rate_limiting()
        
        if not await self.validate_api_dependencies():
            return {
                "status": "uncertain",
                "confidence": 0.1,
                "sources": [],
                "consensus": "Cannot verify - fact-checking tools not available",
                "error": "Web search API not configured"
            }
        
        try:
            # Enhanced search queries
            search_queries = [
                f'"{statement}" fact check site:snopes.com OR site:factcheck.org OR site:politifact.com',
                f'"{statement}" debunked OR verified OR false',
                f'fact check "{statement[:50]}"',  # Truncate long statements
            ]
            
            all_results = []
            for query in search_queries:
                try:
                    results = await self.web_search.search(query, max_results=4)
                    all_results.extend(results)
                except Exception as e:
                    continue  # Continue with other queries if one fails
            
            # Remove duplicates by URL
            seen_urls = set()
            unique_results = []
            for result in all_results:
                if result.url not in seen_urls:
                    seen_urls.add(result.url)
                    unique_results.append(result)
            
            fact_check_sources = []
            weighted_votes = {'false': 0, 'supported': 0, 'contested': 0, 'uncertain': 0}
            
            for result in unique_results[:10]:  # Limit to top 10 results
                tier, base_confidence = self._classify_fact_check_source(result.url)
                status, status_confidence = self._extract_claim_status(result.snippet, result.title)
                
                # Calculate final confidence
                final_confidence = base_confidence * status_confidence
                
                source_data = {
                    "title": result.title,
                    "url": result.url,
                    "tier": tier,
                    "status": status,
                    "confidence": final_confidence,
                    "snippet": result.snippet[:200]  # Truncate snippet
                }
                fact_check_sources.append(source_data)
                
                # Weight votes by tier and confidence
                tier_weight = {"A": 3.0, "B": 2.0, "C": 1.0}[tier]
                vote_weight = final_confidence * tier_weight
                weighted_votes[status] += vote_weight
            
            # Calculate consensus
            if not fact_check_sources:
                return {
                    "status": "uncertain",
                    "confidence": 0.2,
                    "sources": [],
                    "consensus": "No fact-checking sources found for this claim"
                }
            
            # Determine consensus status
            total_weight = sum(weighted_votes.values())
            if total_weight == 0:
                consensus_status = "uncertain"
                consensus_confidence = 0.1
            else:
                consensus_status = max(weighted_votes.items(), key=lambda x: x[1])[0]
                consensus_confidence = min(weighted_votes[consensus_status] / total_weight, 0.95)
            
            # Generate human-readable consensus
            consensus_text = self._generate_consensus_text(consensus_status, consensus_confidence, len(fact_check_sources))
            
            return {
                "status": consensus_status,
                "confidence": round(consensus_confidence, 2),
                "sources": fact_check_sources[:5],  # Return top 5 sources
                "consensus": consensus_text,
                "total_sources_checked": len(fact_check_sources)
            }
            
        except Exception as e:
            return {
                "status": "uncertain",
                "confidence": 0.1,
                "sources": [],
                "consensus": f"Fact-checking failed due to error: {str(e)[:100]}",
                "error": str(e)
            }
    
    def _generate_consensus_text(self, status: str, confidence: float, source_count: int) -> str:
        """Generate human-readable consensus explanation."""
        confidence_level = "high" if confidence >= 0.8 else "moderate" if confidence >= 0.6 else "low"
        
        status_phrases = {
            "false": f"This claim appears to be FALSE with {confidence_level} confidence",
            "supported": f"This claim appears to be TRUE with {confidence_level} confidence", 
            "contested": f"This claim is CONTESTED/MIXED with {confidence_level} confidence",
            "uncertain": f"This claim is UNCERTAIN with {confidence_level} confidence"
        }
        
        base_text = status_phrases.get(status, f"Status: {status} with {confidence_level} confidence")
        return f"{base_text} based on {source_count} fact-checking source(s)."


class ToolRegistry:
    """Enhanced registry for all available tools with health checking and validation."""
    
    def __init__(self):
        self.web_search = WebSearchTool()
        self.url_fetch = URLFetchTool()
        self.reverse_image_search = ReverseImageSearchTool()
        self.youtube_meta = YouTubeMetadataTool()
        self.tiktok_meta = TikTokMetadataTool()
        self.fact_check = FactCheckTool()
        
        # Track tool health status
        self._health_status = {}
        self._last_health_check = 0
        self._health_check_interval = 300  # 5 minutes
    
    async def validate_all_tools(self) -> Dict[str, Dict[str, Any]]:
        """Validate all tools and return their status."""
        import time
        current_time = time.time()
        
        # Use cached health status if recent
        if (current_time - self._last_health_check) < self._health_check_interval and self._health_status:
            return self._health_status
        
        status = {}
        
        # Check Web Search
        try:
            web_search_valid = await self.web_search.validate_api_key()
            status["web_search"] = {
                "available": web_search_valid,
                "configured": self.web_search.is_configured,
                "status": "ready" if web_search_valid else "api_key_required"
            }
        except Exception as e:
            status["web_search"] = {"available": False, "configured": False, "error": str(e)}
        
        # Check Reverse Image Search
        try:
            image_search_valid = await self.reverse_image_search.validate_api_key()
            status["reverse_image_search"] = {
                "available": image_search_valid,
                "configured": self.reverse_image_search.is_configured,
                "status": "ready" if image_search_valid else "api_key_required"
            }
        except Exception as e:
            status["reverse_image_search"] = {"available": False, "configured": False, "error": str(e)}
        
        # Check YouTube
        try:
            youtube_valid = await self.youtube_meta.validate_api_key()
            status["youtube_meta"] = {
                "available": youtube_valid,
                "configured": self.youtube_meta.is_configured,
                "status": "ready" if youtube_valid else "api_key_required"
            }
        except Exception as e:
            status["youtube_meta"] = {"available": False, "configured": False, "error": str(e)}
        
        # TikTok (no API validation needed)
        status["tiktok_meta"] = {
            "available": True,
            "configured": True,
            "status": "ready_basic"  # Basic scraping available
        }
        
        # URL Fetch (always available if aiohttp present)
        status["url_fetch"] = {
            "available": bool(aiohttp),
            "configured": True,
            "status": "ready" if aiohttp else "dependency_missing"
        }
        
        # Fact Check (depends on web search)
        try:
            fact_check_valid = await self.fact_check.validate_api_dependencies()
            status["fact_check"] = {
                "available": fact_check_valid,
                "configured": fact_check_valid,
                "status": "ready" if fact_check_valid else "depends_on_web_search"
            }
        except Exception as e:
            status["fact_check"] = {"available": False, "configured": False, "error": str(e)}
        
        self._health_status = status
        self._last_health_check = current_time
        
        return status
    
    async def get_available_tools(self) -> List[str]:
        """Get list of currently available tool names."""
        status = await self.validate_all_tools()
        return [tool_name for tool_name, tool_status in status.items() if tool_status.get("available", False)]
    
    def get_fallback_message(self, tool_name: str) -> str:
        """Get fallback message when tool is unavailable."""
        messages = {
            "web_search": "Web search unavailable - requires Bing Search API key configuration",
            "reverse_image_search": "Reverse image search unavailable - requires Google Custom Search API",
            "youtube_meta": "YouTube metadata unavailable - requires YouTube Data API key",
            "fact_check": "Fact checking unavailable - depends on web search functionality",
            "url_fetch": "URL fetching unavailable - requires aiohttp dependency"
        }
    async def call_tool_with_monitoring(self, tool_name: str, **kwargs) -> Dict[str, Any]:
        """Call a tool with performance monitoring and error handling."""
        import time
        start_time = time.time()
        
        try:
            # Check if tool is available
            available_tools = await self.get_available_tools()
            if tool_name not in available_tools:
                return {
                    "error": self.get_fallback_message(tool_name),
                    "status": "unavailable",
                    "execution_time": 0
                }
            
            # Call the appropriate tool
            result = None
            if tool_name == "web_search":
                result = await self.web_search.search(
                    kwargs.get("query", ""),
                    kwargs.get("max_results", 5)
                )
            elif tool_name == "fetch_url":
                result = await self.url_fetch.fetch_url(
                    kwargs.get("url", ""),
                    kwargs.get("max_chars", 5000)
                )
            elif tool_name == "reverse_image_search":
                result = await self.reverse_image_search.reverse_search(
                    kwargs.get("image_url", "")
                )
            elif tool_name == "youtube_meta":
                result = await self.youtube_meta.get_metadata(
                    kwargs.get("url_or_guess", "")
                )
            elif tool_name == "tiktok_meta":
                result = await self.tiktok_meta.get_metadata(
                    kwargs.get("url_or_guess", "")
                )
            elif tool_name == "fact_check":
                result = await self.fact_check.check_claim(
                    kwargs.get("statement", "")
                )
            else:
                return {
                    "error": f"Unknown tool: {tool_name}",
                    "status": "unknown_tool",
                    "execution_time": 0
                }
            
            execution_time = time.time() - start_time
            
            return {
                "result": result,
                "status": "success",
                "execution_time": round(execution_time, 2),
                "tool_name": tool_name
            }
            
        except ToolError as e:
            execution_time = time.time() - start_time
            return {
                "error": str(e),
                "status": "tool_error",
                "execution_time": round(execution_time, 2),
                "tool_name": tool_name
            }
        except Exception as e:
            execution_time = time.time() - start_time
            return {
                "error": f"Unexpected error: {str(e)}",
                "status": "unexpected_error",
                "execution_time": round(execution_time, 2),
                "tool_name": tool_name
            }
    
    def get_tool_descriptions(self) -> List[Dict[str, Any]]:
        """Get tool descriptions for LLM function calling."""
        return [
            {
                "name": "web_search",
                "description": "Search the web for information",
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
                "description": "Perform reverse image search",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "image_url": {
                            "type": "string",
                            "description": "URL of the image to search for"
                        }
                    },
                    "required": ["image_url"]
                }
            },
            {
                "name": "yt_meta",
                "description": "Get YouTube video metadata",
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
            return await self.fact_check.check_claim(**kwargs)
        else:
            raise ToolError(f"Unknown tool: {tool_name}")


# Global tool registry instance
tools = ToolRegistry()
