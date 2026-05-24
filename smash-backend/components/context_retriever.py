"""
========================================================
  COMPONENT: Context Retriever
  C4 Diagram Reference: ContextRetriever [Component: python]
========================================================

What is this component?
------------------------
The Context Retriever is responsible for fetching the
architecture context and prompts needed for smell analysis.

Its job from the C4 diagram:
"Calls Context Manager to fetch ADRs + system context
for the given project/commit"

In plain English:
-----------------
In the full production version of SMASH, this component
would connect to the Context Manager system and retrieve:
  - ADR records (Architecture Decision Records)
  - System context documents
  - Project-specific architectural guidelines

In our current version:
-----------------------
We use a built-in prompt template (defined in this file)
that contains all the smell definitions and instructions
for the LLM. This is the same prompt template from the
old version — but now it is language-independent.

This component's job is to:
  1. Take the code, class name, and LANGUAGE from the DetectionRequest
  2. Fill them into the prompt template
  3. Store the filled prompt back into the DetectionRequest
  4. Return the updated DetectionRequest

LANGUAGE INDEPENDENCE CHANGE:
------------------------------
The old prompt said:
  "You are analyzing Java class..."
  "Java code:"

This hardcoded "Java" made the LLM assume Java syntax even
when the file was Python or C#.

Now the prompt uses {language} placeholders:
  "You are analyzing {language} class..."
  "{language} code:"

The language is filled in from request.language, which was
set by the VS Code extension and validated by the
DetectionCoordinator. The smell definitions themselves
remain the same — architectural smells apply to all languages.

Position in pipeline:
---------------------
SourceCodeRetriever
    → ContextRetriever  ← YOU ARE HERE
    → ModelRetriever
    → SmellDetectionEngine
    → ResultsPublisher
"""

# ── Imports ───────────────────────────────────────────────────────────────────
from data.detection_request import DetectionRequest


# ── PROMPTS: Architecture Smell Prompt Template ───────────────────────────────
# This is the prompt that gets sent to the LLM.
# In the C4 diagram this maps to: Prompts [Component: Text]
# "Detail prompts on Architecture smell definition"
#
# Placeholders used (filled in by retrieve_and_build_prompt below):
#   {language}   → the programming language, e.g. "Java", "Python", "C#"
#   {class_name} → the name of the class being analysed
#   {code}       → the full source code of the class
#
# IMPORTANT: Only {language} is new. {class_name} and {code} existed before.
# Changing "Java" to "{language}" is the only modification to the prompt text.
SMELL_ANALYSIS_PROMPT = """You are analyzing a {language} class for software architectural smell indicators.
Class name:
{class_name}
{language} code:
{code}
Task:
Analyze the class for architecture smell indicators.
You may identify any architecture smell that is reasonably visible from the given class.
Do not restrict yourself to only one smell type.
Possible smell categories may include, but are not limited to:
- Cyclic Dependency
- God Component / God Class
- Hub-Like Dependency
- Unstable Dependency
- Cyclic Hierarchy
- Deep Hierarchy
- Wide Hierarchy
Smell guidance:
1. Cyclic Dependency
A Cyclic Dependency smell appears when two or more components depend on each other in a cycle. At class level, direct proof of a cycle is often limited because only one class is shown at a time. Report it only if the code gives strong visible signs that the class participates in circular relationships with other components.
2. God Component / God Class
A God Component smell appears when a component becomes excessively large and concentrates too much logic. It often has low cohesion, growing complexity, and too many responsibilities. At class level, visible signs may include very large code size, many methods, many fields, many different responsibilities, and strong centralization of behavior.
3. Hub-Like Dependency
A Hub-Like Dependency smell appears when a component is highly connected to many other components through many incoming and outgoing dependencies. It often centralizes coordination or logic, can become a unique point of failure, and can increase ripple effects across the system. At class level, visible signs may include many dependencies, many coordinated collaborators, and overly central orchestration.
4. Unstable Dependency
An Unstable Dependency smell appears when a component depends on components that are less stable than itself, making it vulnerable to ripple effects and frequent changes. Since full stability analysis normally needs a system dependency graph, report this smell only if there is visible evidence that the class depends heavily on volatile, implementation-heavy, or unstable-looking collaborators.
5. Cyclic Hierarchy
A Cyclic Hierarchy smell appears when a supertype depends on one of its own subtypes. This can harm reusability, testability, extensibility, and reliability. Report it only if inheritance and dependency relations in the given code strongly suggest that a parent type knows or relies on its child type.
6. Deep Hierarchy
A Deep Hierarchy smell appears when inheritance is excessively deep. This makes behavior harder to predict, increases complexity, complicates change, and can make testing more difficult. At class level, visible signs may include many inheritance levels, repeated overriding, and reliance on behavior inherited from a long chain of supertypes.
7. Wide Hierarchy
A Wide Hierarchy smell appears when a hierarchy is too broad and seems to be missing useful intermediate abstractions. This can reduce understandability, extensibility, reusability, and changeability. At class level, visible signs may include many sibling-like variations with weak abstraction structure or signs that the class belongs to an overly broad inheritance family without proper intermediate layers.
General interpretation rule:
You are given only one {language} class at a time, not the full system dependency graph, not the full package structure, and not the full inheritance graph.
So report only smells that can be reasonably inferred from visible class-level evidence.
Do not invent system-level evidence that is not visible in the code.
Evidence guidance:
- Prefer smells that are directly supported by the visible code
- Be cautious with graph-based smells such as Cyclic Dependency, Hub-Like Dependency, and Unstable Dependency
- Be cautious with hierarchy-based smells such as Cyclic Hierarchy, Deep Hierarchy, and Wide Hierarchy unless inheritance evidence is clearly visible
- If evidence is weak, unclear, or not visible, return []
If no smell is reasonably visible, return exactly:
[]
Return ONLY a valid JSON array.
Do not write markdown.
Do not write explanation outside JSON.
Do not use code fences.
Each item in the JSON array must have exactly these fields:
- smell_category
- smell_severity
- smell_location
- smell_description
- smell_suggestion
Rules:
- smell_category must be the most suitable smell name based on the visible evidence
- smell_severity must be an integer from 1 to 10
- smell_location must mention the class name
- smell_description must be short and specific
- smell_suggestion must be a concrete refactoring suggestion
- only report smells that have reasonable evidence in the class
- if multiple smells are clearly visible, return multiple JSON objects"""


# ── MODULE: Context Retriever ─────────────────────────────────────────────────
class ContextRetriever:
    """
    Fetches architecture context and builds the analysis prompt.
    Fills the prompt template with language, class name, and code.

    C4 Reference: ContextRetriever [Component: python]
    Also uses:    Prompts [Component: Text]
    """

    def retrieve_and_build_prompt(self, request: DetectionRequest) -> DetectionRequest:
        """
        Fills the prompt template with the language, class name, and code
        from the DetectionRequest, and stores the result back
        into request.prompt.

        Parameters:
        -----------
        request : DetectionRequest
            Must have 'language', 'class_name', and 'code' populated.

        Returns:
        --------
        DetectionRequest
            Same object with 'prompt' field now populated.
        """

        # ── Step 1: Log what we are doing ─────────────────────────────────────
        print(f"[ContextRetriever] Fetching architecture context and building prompt")
        print(f"  Class   : {request.class_name}")
        print(f"  Language: {request.language}")

        # ── Step 2: Fill the prompt template ──────────────────────────────────
        # .format() replaces the {language}, {class_name}, and {code}
        # placeholders with the actual values from the DetectionRequest.
        #
        # WHAT CHANGED vs old version:
        # Old: SMELL_ANALYSIS_PROMPT.format(class_name=..., code=...)
        # New: SMELL_ANALYSIS_PROMPT.format(language=..., class_name=..., code=...)
        #
        # The only addition is language=request.language.
        # This single change makes the entire prompt language-aware.
        filled_prompt = SMELL_ANALYSIS_PROMPT.format(
            language=request.language,
            class_name=request.class_name,
            code=request.code
        )

        # ── Step 3: Store the filled prompt in the DetectionRequest ───────────
        # We do NOT create a new object — we update the existing one.
        # This filled prompt will be read by SmellDetectionEngine next.
        request.prompt = filled_prompt

        # ── Step 4: Log success ───────────────────────────────────────────────
        print(f"[ContextRetriever] Prompt built successfully")
        print(f"  Prompt length: {len(filled_prompt)} characters")

        # ── Step 5: Return the updated DetectionRequest ───────────────────────
        return request
