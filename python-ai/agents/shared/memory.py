import json
import os
from datetime import datetime

class MemoryStore:
    def __init__(self, storage_dir="memory_store"):
        self.storage_dir = storage_dir
        if not os.path.exists(self.storage_dir):
            os.makedirs(self.storage_dir)

    async def remember(self, repo: str, memory_type: str, narrative: str, metadata: dict):
        """
        Stores an episodic memory. In production, this saves to a Vector DB like Qdrant
        so the Planner Agent can retrieve it when solving similar bugs in the future.
        """
        memory_id = datetime.now().strftime("%Y%m%d_%H%M%S")
        repo_safe = repo.replace("/", "_")
        
        record = {
            "id": memory_id,
            "repo": repo,
            "type": memory_type,
            "narrative": narrative,
            "confidence": metadata.get("confidence", 0.5),
            "feedback": metadata.get("feedback", "UNKNOWN"),
            "timestamp": datetime.utcnow().isoformat()
        }
        
        filepath = os.path.join(self.storage_dir, f"{repo_safe}_{memory_type}_{memory_id}.json")
        with open(filepath, "w") as f:
            json.dump(record, f, indent=2)
            
        print(f"\n[MEMORY SAVED] Type: {memory_type} | Confidence: {record['confidence']}")
        print(f"Narrative: {narrative}")
