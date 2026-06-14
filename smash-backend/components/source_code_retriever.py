"""
========================================================
  COMPONENT: Source Code Retriever
  C4 Diagram Reference: SourceCodeRetriever [Component: python]
========================================================

What is this component?
------------------------
The Source Code Retriever is responsible for retrieving
and preparing the source code artifacts for analysis.

Its job from the C4 diagram:
"Retrieves source files, code changes, and related artifacts"

In plain English:
-----------------
In the full production version of SMASH, this component
would connect to a Source Code Repository (like Git) and
pull the actual source files, diffs, and change history.

In our current version:
-----------------------
Since we do not have a separate Git repository connected,
the source code is already inside the DetectionRequest
(sent directly by the VS Code extension).

So this component's job right now is to:
  1. Read the code from the DetectionRequest
  2. Extract metadata about it — like line count,
     number of classes, and language-specific info
  3. Log what it found so we can see it in the terminal
  4. Return the DetectionRequest (unchanged but logged)

This component is designed to be EASILY EXPANDABLE —
in the future, you could add Git integration here without
changing any other component.

Position in pipeline:
---------------------
Detection Coordinator
    → SourceCodeRetriever  ← YOU ARE HERE
    → ContextRetriever
    → ModelRetriever
    → SmellDetectionEngine
    → ResultsPublisher
"""

# ── Imports ───────────────────────────────────────────────────────────────────
import re  # re = regular expressions, used to count classes in the code
from data.detection_request import DetectionRequest


# ── MODULE: Source Code Retriever ─────────────────────────────────────────────
class SourceCodeRetriever:
    """
    Retrieves and prepares source code artifacts for analysis.

    C4 Reference: SourceCodeRetriever [Component: python]
    """

    def retrieve(self, request: DetectionRequest) -> DetectionRequest:
        """
        Reads the source code from the DetectionRequest,
        extracts language-aware metadata, and logs what was found.

        Parameters:
        -----------
        request : DetectionRequest
            The data object from Detection Coordinator.
            Must have 'code', 'class_name', and 'language' populated.

        Returns:
        --------
        DetectionRequest
            The same request object, unchanged.
            Ready for ContextRetriever to process next.
        """

        # ── Step 1: Count lines in the code ───────────────────────────────────
        # splitlines() splits the code string into a list of individual lines.
        # len() counts how many lines there are.
        # This works the same for every language.
        line_count = len(request.code.splitlines())

        # ── Step 2: Extract language-specific metadata ─────────────────────────
        # We call the right helper method depending on the language.
        # Each helper returns a small dict of metadata for logging.
        #
        # request.language comes from the VS Code extension.
        # We use .lower() so "Java", "java", "JAVA" all match correctly.
        language_lower = request.language.lower()

        if language_lower == "java":
            metadata = self._extract_java_metadata(request.code)

        elif language_lower == "python":
            metadata = self._extract_python_metadata(request.code)

        elif language_lower in ("csharp", "c#"):
            metadata = self._extract_csharp_metadata(request.code)

        elif language_lower in ("cpp", "c++"):
            metadata = self._extract_cpp_metadata(request.code)

        else:
            # For TypeScript, JavaScript, or any other language,
            # we do a simple generic count that works for most languages.
            metadata = self._extract_generic_metadata(request.code)

        # ── Step 3: Log what we found ─────────────────────────────────────────
        # These print statements appear in the start_backend terminal window.
        # They help us see exactly what source code was retrieved.
        print(f"[SourceCodeRetriever] Retrieved source code artifacts:")
        print(f"  Class name : {request.class_name}")
        print(f"  Language   : {request.language}")
        print(f"  Total lines: {line_count}")

        # Print each metadata item returned by the language-specific helper
        for key, value in metadata.items():
            print(f"  {key}: {value}")

        # ── Step 4: Return the request ────────────────────────────────────────
        # We return the same DetectionRequest object.
        # The code is already inside it — no changes needed.
        # The next component (ContextRetriever) will add the prompt to it.
        return request

    # ── LANGUAGE-SPECIFIC METADATA HELPERS ────────────────────────────────────
    
    # Each method below is private (underscore prefix) because they are
    # internal helpers only meant to be called by retrieve() above.
    # They all return a dict of { "label": value } pairs for logging.
    #
    # To add a new language:
    #   1. Add a new elif in retrieve() pointing to a new helper method
    #   2. Write the helper method below following the same pattern
    #   No other file needs to change.

    def _extract_java_metadata(self, code: str) -> dict:
        """
        Extracts Java-specific metadata: package name and class count.

        Java files usually start with:  package com.example.myapp;
        Java classes are declared as:   public class MyClass { ... }
        """

        # Count class declarations using regex
        # (?:...) means: match this group but don't capture it
        # \\s+ means: one or more spaces
        # \\w+ means: one or more word characters (the class name)
        class_count = len(re.findall(
            r'(?:public\s+)?(?:abstract\s+)?(?:final\s+)?class\s+\w+',
            code
        ))

        # Find the package declaration if present
        # re.MULTILINE makes ^ match the start of each line, not just the file
        package_match = re.search(r'^package\s+([\w.]+);', code, re.MULTILINE)
        package_name = package_match.group(1) if package_match else "default package"

        return {
            "Package      ": package_name,
            "Classes found": class_count
        }

    def _extract_python_metadata(self, code: str) -> dict:
        """
        Extracts Python-specific metadata: class and function counts.

        Python classes are declared as:    class MyClass:
        Python functions are declared as:  def my_function():
        """

        # Count class declarations: "class " followed by a name
        class_count = len(re.findall(r'^class\s+\w+', code, re.MULTILINE))

        # Count top-level function/method definitions
        def_count = len(re.findall(r'^\s*def\s+\w+', code, re.MULTILINE))

        return {
            "Classes found": class_count,
            "Methods/funcs": def_count
        }

    def _extract_csharp_metadata(self, code: str) -> dict:
        """
        Extracts C#-specific metadata: namespace and class counts.

        C# namespaces:  namespace MyApp.Services { ... }
        C# classes:     public class MyService { ... }
        """

        # Count class declarations (also matches record, struct for completeness)
        class_count = len(re.findall(
            r'(?:public\s+|private\s+|internal\s+)?(?:abstract\s+|sealed\s+)?class\s+\w+',
            code
        ))

        # Find the namespace declaration if present
        namespace_match = re.search(r'namespace\s+([\w.]+)', code)
        namespace_name = namespace_match.group(1) if namespace_match else "no namespace"

        return {
            "Namespace    ": namespace_name,
            "Classes found": class_count
        }

    def _extract_cpp_metadata(self, code: str) -> dict:
        """
        Extracts C++-specific metadata: class count and include count.

        C++ classes:  class MyClass { ... }
        C++ includes: #include <iostream>  or  #include "myheader.h"
        """

        class_count = len(re.findall(r'\bclass\s+\w+', code))

        # Count #include directives — gives a sense of how many dependencies
        include_count = len(re.findall(r'^\s*#include', code, re.MULTILINE))

        return {
            "Classes found": class_count,
            "Includes     ": include_count
        }

    def _extract_generic_metadata(self, code: str) -> dict:
        """
        Generic metadata extraction for any other language.
        Works for TypeScript, JavaScript, Go, Kotlin, Swift, etc.

        Most languages use the keyword "class" for class declarations,
        so this simple regex covers the majority of cases.
        """

        # A simple class count that works for most languages
        class_count = len(re.findall(r'\bclass\s+\w+', code))

        # Count non-empty lines as a measure of code density
        non_empty_lines = len([l for l in code.splitlines() if l.strip()])

        return {
            "Classes found    ": class_count,
            "Non-empty lines  ": non_empty_lines
        }
