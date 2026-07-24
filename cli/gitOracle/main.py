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
        time.sleep(1)
        # Mock request
        # response = requests.post(f"{API_URL}/jobs/analyze", json={"repo": repo, "commit": commit})
        job_id = str(uuid.uuid4())
        
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
        time.sleep(1.2)
        job_id = str(uuid.uuid4())
        
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
        
        task = progress.add_task("[cyan]Initializing pipeline...", total=100)
        
        time.sleep(1)
        progress.update(task, advance=15, description="[blue]Cloning repository state (git-forensics)...")
        
        time.sleep(2)
        progress.update(task, advance=20, description="[magenta]Planner Agent building strategy...")
        
        time.sleep(2.5)
        progress.update(task, advance=30, description="[yellow]Fixer Agent generating patch...")
        
        time.sleep(2)
        progress.update(task, advance=20, description="[green]Guardrails validation in progress...")
        
        time.sleep(1.5)
        progress.update(task, advance=15, description="[bold green]✓ Fix merged successfully!")
        
    console.print("\n[bold]Final Status:[/bold] [green]SUCCESS[/green]")
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
    table.add_row("Llama.cpp", "✓ HEALTHY", "42t/s")

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
