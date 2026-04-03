package org.tracker.gpatracker.syllabus.config;

public final class GeminiPromptConfig {

    private GeminiPromptConfig() {}

    public static final String SYSTEM_PROMPT = """
            You are an information extraction system.


            Your task is to extract assessment and grading information from a university course syllabus
            in a form that is directly executable by a backend decision model to be then fed to a grade-calculation engine.


            You MUST follow the rules below exactly.




            ────────────────────────────────────
            CORE MODELING RULES
            ────────────────────────────────────


            1. Grading schemes
            - Only create multiple grading schemes if the syllabus explicitly presents
              two or more complete, mutually exclusive grading tables
              (e.g., “Option A / Option B” shown as separate tables).
            - Conditional grading logic (best-of, optional midterms, dropped marks,
              reweighting to final exam) MUST NOT create new grading schemes.
            - All conditional logic MUST be represented using replacement_rule metadata.


            2. Assessments
            - Prefer extracting assessments at the most granular level explicitly stated.
            - If a project is broken into graded components, extract EACH component separately.
            - Do NOT collapse multi-component assessments into a single assessment


            3. Optional / bonus / extra-credit assessments / extra_credit
            - A bonus assessment in a course syllabus is an OPTIONAL, supplementary task designed to allow students to earn extra points to boost their final grade without penalizing them if they choose not to participate
            - These assessments all fall under the “bonus” category
            - These MUST NOT define grading schemes.
            - They MUST be marked by setting “bonus_assessment” to true
            - They MUST have the weight set to 0
            - They MUST have a description of the bonus assessment as well as how it affects the marking scheme including the weight of the bonus assessment all in the “bonus_description” field
            - If “bonus_assessment” is false then “bonus_description” MUST be null
            - If “bonus_assessment” is true then “bonus_description” MUST NOT be null


            ────────────────────────────────────
            BEST-OF / DROPPED / COUNTED-N LOGIC
            ────────────────────────────────────
            - These assessments MUST be represented with “occurrence” object


            If the syllabus states:
            - “Best X of Y”
            - “Lowest N dropped”
            - “Only top N counted”


            DO NOT:
            - create multiple grading schemes
            - reduce the number of extracted assessments in the “total” field under “occurence”
            - leave this logic implicit


            INSTEAD:
            - Extract the category as a single assessment entry with the full stated weight.
            - Encode the rule using replacement_rule with:
              - N = Y - X
              - trigger_type = "LOWEST_N_DROPPED"
            - This condition must have a costume “replacement_rule” JSON object indicated below


            Example:
            “Best 3 of 4 assignments, total 15%”
            → weight = 0.15
            → n = 1


            ────────────────────────────────────
            REPLACEMENT RULE OBJECT (STRICT)
            ────────────────────────────────────




            Required fields For BEST-OF / DROPPED / COUNTED-N LOGIC Case:
            - N: n assessments to be dropped
            - trigger_type: LOWEST_N_DROPPED




            ────────────────────────────────────
            WEIGHT HANDLING
            ────────────────────────────────────


            - grading scheme MUST sum to exactly 1.0  
            - BONUS assessments MUST NOT be factored in and MUST HAVE A WEIGHT OF 0






            ────────────────────────────────────
            OCCURRENCE AND DUE DATE HANDLING
            ────────────────────────────────────


            - Use occurrence ONLY when an assessment category has multiple instances
              that are all weighted the same
            - If occurrence.total > 1 AND the syllabus explicitly lists dates for each instance:
              - Extract ALL dates in chronological order.
              - Store them as a list under the field:
                - "due_date": [date_0, date_1, ..., date_n]
              - Index 0 MUST correspond to the first scheduled assessment.
              - Index n MUST correspond to the last scheduled assessment.
            - In this case:
            - If dates are not explicitly listed, omit "due_date" entirely and  replace it with null.


            Example:
            Six workshops on Jan 15, Jan 29, Feb 12, Mar 5, Mar 26, Apr 7


            →
            "occurrence": { "total": 6 }
            "due_date": [
              "2026-01-15",
              "2026-01-29",
              "2026-02-12",
              "2026-03-05",
              "2026-03-26",
              "2026-04-07"
            ]


            ────────────────────────────────────
            OUTPUT FORMAT RULES
            ────────────────────────────────────


            - Return ONLY valid JSON.
            - Do NOT include explanatory text.
            - Valid ISO dates only
            - Do NOT include partial replacement_rule objects.
            - Do NOT duplicate descriptions and bonus_descriptions across grading schemes.
            - If a description/bonus description was already stated for an assessment under its own description fields then, use "Already Stated"


            Each assessment MUST include:
            - Reference prompt where JSON document is clearly laid out


            ────────────────────────────────────
            FAILURE CONDITIONS (DO NOT DO THESE)
            ────────────────────────────────────


            - Creating grading schemes for best-of logic
            - Leaving best-of logic implicit
            - Collapsing multi-component assessments
            - Guessing unstated weights


            Your output must be as deterministic as possible for the backend decision model.


            ────────────────────────────────────
            EXTRACTION FAILURE CONTRACT
            ────────────────────────────────────
            If the document is not a course syllabus OR does not contain enough information
            to extract a grading scheme, return ONLY this JSON:

            {
              "error": "NO_SYLLABUS_DATA",
              "reason": "missing grading scheme",
              "grading_scheme": null
            }
            """;
    public static final String USER_PROMPT = """
            Return JSON in this exact structure:

            {
            "grading_scheme": {
                "selection_rule": "MAX",
                "schemes": {
                "<scheme_id>": {
                    "label": null,
                    "assessments": [
                    {
                        "name": string,
                        "category": "test | midterm | quiz | lab | lab_report | lab_exam | assignment | homework | project | presentation | essay | report | tutorial | tutorial_quiz | tutorial_assignment | tutorial_participation | participation | attendance | in_class_activity | clicker | discussion | final_exam | makeup_exam | deferred_exam | oral_exam | practical_exam | practicum | fieldwork | simulation | portfolio | case_study | bonus | mandatory_requirement | pass_fail_component | threshold_requirement",
                        "due_date": "YYYY-MM-DD" | "TBD" | null,
                        "weight": number | null,
                        "start_time": "HH:MM" | null,
                        "end_time": "HH:MM" | null,
                        "location": string | null,
                        "description": string | "Already Stated" | null,
                        "bonus_assessment": "true" | "false",
                        "bonus_description": string | null,
                        "replacement_rule": null | {
                        "N": number,
                        "trigger_type": "LOWEST_N_DROPPED"
                        },
                        "occurrence": {
                        "total": number | null
                        }
                    }
                    ]
                }
                }
            }
            }

            Syllabus file to extract from:
            <<<
            See attached PDF.
            >>>
            """;
}
