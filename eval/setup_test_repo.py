import os
import shutil
import subprocess
import json
from pathlib import Path

REPO_PATH = "/tmp/gitoracle-eval-repo"
GOLDEN_DIR = Path(__file__).parent / "golden"

def run_git(*args, cwd=REPO_PATH):
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)

def setup_repo():
    if os.path.exists(REPO_PATH):
        shutil.rmtree(REPO_PATH)
    
    os.makedirs(REPO_PATH)
    run_git("init")
    # Setup dummy user
    run_git("config", "user.email", "test@gitoracle.ai")
    run_git("config", "user.name", "Test User")

    if os.path.exists(GOLDEN_DIR):
        shutil.rmtree(GOLDEN_DIR)
    os.makedirs(GOLDEN_DIR)

    # 1. Base Commit (No bugs)
    java_file_path = os.path.join(REPO_PATH, "src/main/java/com/example/UserService.java")
    os.makedirs(os.path.dirname(java_file_path), exist_ok=True)
    with open(java_file_path, "w") as f:
        f.write("""package com.example;

public class UserService {
    private PaymentGateway paymentGateway;

    public UserService(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    public void processPayment(User user, PaymentInfo payment) {
        String userId = user.getId();
        paymentGateway.charge(userId, payment.getAmount());
    }
}
""")
    user_file_path = os.path.join(REPO_PATH, "src/main/java/com/example/User.java")
    with open(user_file_path, "w") as f:
        f.write("""package com.example;
public class User {
    private String id;
    public String getId() { return id; }
}
""")
    payment_file_path = os.path.join(REPO_PATH, "src/main/java/com/example/PaymentInfo.java")
    with open(payment_file_path, "w") as f:
        f.write("""package com.example;
public class PaymentInfo {
    private double amount;
    public double getAmount() { return amount; }
}
""")
    gateway_file_path = os.path.join(REPO_PATH, "src/main/java/com/example/PaymentGateway.java")
    with open(gateway_file_path, "w") as f:
        f.write("""package com.example;
public class PaymentGateway {
    public void charge(String userId, double amount) {}
}
""")

    run_git("add", ".")
    run_git("commit", "-m", "Initial commit: Add UserService and models")

    # CASE 1: Null Pointer Exception
    # We introduce a bug where `user` can be null
    run_git("checkout", "-b", "case1-npe")
    # Actually, we don't need branches, we just create a commit on main, then get its SHA.
    # Wait, the bug is already in the initial commit if `user` can be null.
    # Let's create a specific commit that introduced a bug.
    with open(java_file_path, "w") as f:
        f.write("""package com.example;

public class UserService {
    private PaymentGateway paymentGateway;

    public UserService(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }

    public void processPayment(User user, PaymentInfo payment) {
        // BUG: user can be null if fetched from DB incorrectly
        String userId = user.getId();
        paymentGateway.charge(userId, payment.getAmount());
    }
}
""")
    run_git("add", ".")
    run_git("commit", "-m", "feat: update processPayment")
    sha1 = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO_PATH).decode().strip()
    
    case1_dir = GOLDEN_DIR / "case1_npe"
    os.makedirs(case1_dir)
    with open(case1_dir / "bug_description.txt", "w") as f:
        f.write("NullPointerException in UserService.java line 11 (user.getId()). The user object can sometimes be null.")
    with open(case1_dir / "expected_root_commit.txt", "w") as f:
        f.write(sha1)
    with open(case1_dir / "expected_fix.patch", "w") as f:
        f.write("""--- src/main/java/com/example/UserService.java
+++ src/main/java/com/example/UserService.java
@@ -10,6 +10,9 @@
     }
 
     public void processPayment(User user, PaymentInfo payment) {
+        if (user == null) {
+            throw new IllegalArgumentException("User cannot be null");
+        }
         // BUG: user can be null if fetched from DB incorrectly
         String userId = user.getId();
         paymentGateway.charge(userId, payment.getAmount());
""")

    # CASE 2: Logic Error / Out of Bounds
    run_git("checkout", "main")
    processor_path = os.path.join(REPO_PATH, "src/main/java/com/example/BatchProcessor.java")
    with open(processor_path, "w") as f:
        f.write("""package com.example;
import java.util.List;

public class BatchProcessor {
    public void process(List<String> items) {
        // BUG: <= size() will throw IndexOutOfBoundsException
        for (int i = 0; i <= items.size(); i++) {
            System.out.println(items.get(i));
        }
    }
}
""")
    run_git("add", ".")
    run_git("commit", "-m", "feat: add BatchProcessor")
    sha2 = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO_PATH).decode().strip()
    
    case2_dir = GOLDEN_DIR / "case2_ioob"
    os.makedirs(case2_dir)
    with open(case2_dir / "bug_description.txt", "w") as f:
        f.write("IndexOutOfBoundsException in BatchProcessor.java line 8 (items.get(i)). The loop iterates past the end of the list.")
    with open(case2_dir / "expected_root_commit.txt", "w") as f:
        f.write(sha2)
    with open(case2_dir / "expected_fix.patch", "w") as f:
        f.write("""--- src/main/java/com/example/BatchProcessor.java
+++ src/main/java/com/example/BatchProcessor.java
@@ -5,7 +5,7 @@
 public class BatchProcessor {
     public void process(List<String> items) {
-        // BUG: <= size() will throw IndexOutOfBoundsException
-        for (int i = 0; i <= items.size(); i++) {
+        for (int i = 0; i < items.size(); i++) {
             System.out.println(items.get(i));
         }
     }
""")

    print(f"Setup complete. Repo at {REPO_PATH}, Golden dataset at {GOLDEN_DIR}")

if __name__ == "__main__":
    setup_repo()
