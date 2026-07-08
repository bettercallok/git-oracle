import asyncio
from pydantic import BaseModel, Field
import httpx
import sys
import os

# Add ai_core to path to import shared module
sys.path.append(os.path.dirname(__file__))
from shared.structured_output import llm_structured

# Define our strict output schema
class BugReport(BaseModel):
    title: str = Field(description="A short, descriptive title of the bug")
    severity: str = Field(description="Either 'LOW', 'MEDIUM', 'HIGH', or 'CRITICAL'")
    root_cause_analysis: str = Field(description="A detailed explanation of why the bug occurred")
    affected_files: list[str] = Field(description="A list of file paths affected by this bug")

async def main():
    print("Sending request to LLM Server on port 8090...")
    
    messages = [
        {"role": "system", "content": "You are an expert AI debugging agent. Analyze the provided error and return a detailed bug report."},
        {"role": "user", "content": "The Java application crashes on startup with: 'NullPointerException in ai.gitoracle.gateway.ApiGatewayApplication.main(ApiGatewayApplication.java:23)'. It seems the Kafka configuration is missing from application.yml."}
    ]
    
    try:
        # This will force the LLM to return valid JSON matching BugReport
        result = await llm_structured(messages=messages, output_schema=BugReport)
        
        print("\n✅ SUCCESS! Received perfectly parsed Pydantic object:")
        print("-" * 50)
        print(f"Title: {result.title}")
        print(f"Severity: {result.severity}")
        print(f"Files: {result.affected_files}")
        print(f"RCA: {result.root_cause_analysis}")
        print("-" * 50)
        
    except httpx.HTTPStatusError as e:
        print(f"❌ HTTP FAILED: {e.response.status_code}")
        print(e.response.text)
    except Exception as e:
        print(f"❌ FAILED: {type(e).__name__} - {e}")

if __name__ == "__main__":
    asyncio.run(main())
