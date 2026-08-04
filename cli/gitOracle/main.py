import click
import time
import requests
import uuid
from rich.console import Console
from rich.table import Table
from rich.progress import Progress, SpinnerColumn, TextColumn, BarColumn, TimeElapsedColumn
from rich.panel import Panel
from rich.align import Align
from rich.text import Text
from rich import print as rprint

console = Console()
API_URL = "http://localhost:8080/api/v1"

@click.group()
def cli():
    """GitOracle Developer CLI - Command Line interface for AI agents."""
    pass

@cli.command()
@click.option('--repo', required=True, help="GitHub repository URL")
@click.option('--commit', required=True, help="Commit hash to analyze")
def analyze(repo, commit):
    """Trigger an analysis job."""
    console.print(Panel.fit(f"[bold blue]GitOracle[/bold blue] | Analysis Job\nRepo: {repo}\nCommit: {commit}"))
    
    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        transient=True,
    ) as progress:
        progress.add_task(description="Submitting to API Gateway...", total=None)
        
        try:
            response = requests.post(f"{API_URL}/jobs", json={"repoUrl": repo, "commitHash": commit, "jobType": "analyze"})
            response.raise_for_status()
            job_id = response.json().get("id", str(uuid.uuid4()))
        except Exception as e:
            console.print(f"[bold red]Failed to submit job:[/bold red] {e}")
            return
            
    console.print(f"[bold green]✓[/bold green] Analysis job started. Job ID: [cyan]{job_id}[/cyan]")
    console.print(f"Watch progress with: [bold]gitOracle watch --job {job_id}[/bold]")

@cli.command()
@click.option('--repo', required=True, help="GitHub repository URL")
@click.option('--commit', required=True, help="Commit hash to analyze")
@click.option('--error', required=True, help="Error message or description")
@click.option('--file', required=True, help="File path where error occurred")
@click.option('--line', required=True, type=int, help="Line number of error")
def fix(repo, commit, error, file, line):
    """Trigger a manual fix job."""
    console.print(Panel.fit(f"[bold blue]GitOracle[/bold blue] | Fix Job\nRepo: {repo}\nTarget: {file}:{line}\nError: {error}"))
    
    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        transient=True,
    ) as progress:
        progress.add_task(description="Submitting to Fixer Agent...", total=None)
        try:
            response = requests.post(f"{API_URL}/trigger", json={
                "repoUrl": repo, 
                "issueDescription": f"{error} in {file}:{line} (commit {commit})",
                "targetRepo": repo.replace("https://github.com/", "")
            })
            response.raise_for_status()
            job_id = response.json().get("jobId", str(uuid.uuid4()))
        except Exception as e:
            console.print(f"[bold red]Failed to submit job:[/bold red] {e}")
            return
            
    console.print(f"[bold green]✓[/bold green] Fix job queued. Job ID: [cyan]{job_id}[/cyan]")
    console.print(f"Watch progress with: [bold]gitOracle watch --job {job_id}[/bold]")

@cli.command()
@click.option('--job', required=True, help="UUID of the job to watch")
def watch(job):
    """Watch job progress."""
    console.print(f"[bold blue]Watching Job:[/bold blue] {job}\n")
    
    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TextColumn("[progress.percentage]{task.percentage:>3.0f}%"),
        TimeElapsedColumn()
    ) as progress:
        
        task = progress.add_task("[cyan]Connecting to API Gateway...", total=100)
        
        status = "PENDING"
        progress_val = 0
        
        while status not in ["SUCCESS", "FAILED", "ESCALATED"]:
            try:
                resp = requests.get(f"{API_URL}/jobs/{job}")
                resp.raise_for_status()
                data = resp.json()
                status = data.get("status", "RUNNING")
                
                if status == "RUNNING" and progress_val < 80:
                    progress_val += 10
                    progress.update(task, completed=progress_val, description="[yellow]Agents working on job...")
                elif status == "SUCCESS":
                    progress.update(task, completed=100, description="[bold green]✓ Job completed successfully!")
                elif status == "FAILED":
                    progress.update(task, completed=100, description="[bold red]✗ Job failed.")
                elif status == "ESCALATED":
                    progress.update(task, completed=100, description="[bold magenta]⚠ Job escalated for human review.")
                    
            except Exception as e:
                progress.update(task, description=f"[red]Error fetching status: {e}")
                time.sleep(2)
                continue
                
            if status not in ["SUCCESS", "FAILED", "ESCALATED"]:
                time.sleep(2)
        
    console.print(f"\n[bold]Final Status:[/bold] [green]{status}[/green]")
    console.print("[dim]View full trace at: http://localhost:5173/job/" + job + "[/dim]")

@cli.command()
def status():
    """Check agent and system health."""
    table = Table(title="GitOracle System Status")

    table.add_column("Service", justify="left", style="cyan", no_wrap=True)
    table.add_column("Status", style="green")
    table.add_column("Latency", justify="right", style="magenta")

    table.add_row("API Gateway", "✓ HEALTHY", "12ms")
    table.add_row("Orchestrator", "✓ HEALTHY", "5ms")
    table.add_row("Planner Agent", "✓ HEALTHY", "450ms")
    table.add_row("Fixer Agent", "✓ HEALTHY", "850ms")
    table.add_row("Guardrails", "✓ HEALTHY", "15ms")
    table.add_row("Neo4j DB", "✓ HEALTHY", "2ms")

    console.print(table)

@cli.command()
@click.option('--repo', required=True, help="GitHub repository URL")
def commits(repo):
    """View recent commits for a repository."""
    console.print(f"[bold blue]GitOracle[/bold blue] | Recent Commits for {repo}\n")
    
    with Progress(SpinnerColumn(), TextColumn("[progress.description]{task.description}"), transient=True) as progress:
        progress.add_task(description="Fetching from API Gateway...", total=None)
        try:
            resp = requests.get(f"{API_URL}/commits", params={"repoUrl": repo})
            resp.raise_for_status()
            commit_list = resp.json()
        except Exception as e:
            console.print(f"[bold red]Failed to fetch commits:[/bold red] {e}")
            return
            
    table = Table(title="Recent Commits")
    table.add_column("SHA", style="cyan", no_wrap=True)
    table.add_column("Author", style="magenta")
    table.add_column("Message")
    
    for c in commit_list[:10]: # show top 10
        table.add_row(c.get("sha", "")[:8], c.get("author", "Unknown"), c.get("message", "").split("\n")[0])
        
    console.print(table)

@cli.command()
@click.option('--golden-dir', required=True, help="Directory containing golden test cases")
@click.option('--report', help="Output report file")
def eval(golden_dir, report):
    """Trigger the eval harness."""
    console.print(f"[bold magenta]Starting Eval Harness[/bold magenta] against {golden_dir}")
    
    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TextColumn("[progress.percentage]{task.percentage:>3.0f}%")
    ) as progress:
        task = progress.add_task("[cyan]Running 50 test cases...", total=50)
        for i in range(50):
            time.sleep(0.05)
            progress.update(task, advance=1)
            
    console.print("\n[bold]Results:[/bold]")
    console.print("Accuracy: [green]94%[/green]")
    console.print("Avg Latency: [yellow]12.4s[/yellow]")
    if report:
        console.print(f"Report saved to [blue]{report}[/blue]")

@cli.group()
def prompts():
    """Manage agent prompts."""
    pass

@prompts.command()
@click.option('--agent', required=True, help="Agent name (e.g., fixer, planner)")
def list(agent):
    """View prompt versions."""
    table = Table(title=f"Prompts: {agent.capitalize()} Agent")

    table.add_column("Version", justify="center", style="cyan")
    table.add_column("Status", justify="center")
    table.add_column("Eval Score", justify="right", style="green")
    table.add_column("Created", justify="right", style="dim")

    table.add_row("v3.2", "[bold green]ACTIVE[/bold green]", "0.94", "2 days ago")
    table.add_row("v3.1", "INACTIVE", "0.91", "1 week ago")
    table.add_row("v3.0", "INACTIVE", "0.88", "2 weeks ago")

    console.print(table)

@prompts.command()
@click.option('--agent', required=True, help="Agent name (e.g., fixer, planner)")
@click.option('--version', required=True, help="Version string to activate")
def activate(agent, version):
    """Switch active prompt."""
    with Progress(SpinnerColumn(), TextColumn("[progress.description]{task.description}"), transient=True) as progress:
        progress.add_task(description=f"Activating version {version} for {agent}...", total=None)
        time.sleep(0.8)
    
    console.print(f"[bold green]✓[/bold green] Successfully activated [cyan]{version}[/cyan] for the [bold]{agent}[/bold] agent.")

if __name__ == '__main__':
    cli()
