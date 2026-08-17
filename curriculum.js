/**
 * 72-Week AI Engineering Curriculum & Knowledge Seeds
 */
const CURRICULUM = {
  phases: [
    {
      id: "p1", name: "1. Foundations", region: "Phase 1 • No-Code Foundations",
      stages: [
        { id: "s1", weeks: "Weeks 1–2", title: "Elements of AI", org: "Univ. of Helsinki", tier: "A", code: "No Code", focus: "Core AI/ML concepts — conceptual on-ramp, zero code.", link: "https://www.elementsofai.com/", xp: 100,
          flashcards: [
            { id: "s1_c1", q: "Why is an Euler diagram used instead of a Venn diagram in AI taxonomy?", a: "Venn diagrams show all theoretically possible intersections; Euler diagrams show only relationships that physically exist in reality." },
            { id: "s1_c2", q: "What defines a Nearest-Neighbor classifier decision boundary?", a: "Voronoi tessellation cells where space is partitioned based on proximity to labeled training instances under Euclidean or Manhattan distance." }
          ]
        },
        { id: "s2", weeks: "Weeks 3–4", title: "Ethics of AI", org: "Univ. of Helsinki", tier: "A", code: "No Code", focus: "Responsible AI, societal impact, bias frameworks.", link: "https://ethics-of-ai.mooc.fi/", xp: 100,
          flashcards: [
            { id: "s2_c1", q: "What is the difference between historical bias and measurement bias?", a: "Historical bias reflects existing societal inequities even with accurate sampling; measurement bias arises from flawed data proxies, labeling errors, or sensor noise." }
          ]
        }
      ]
    },
    {
      id: "p2", name: "2. Python Bridge", region: "Phase 2 • Syntax & API Calls",
      stages: [
        { id: "s3", weeks: "Weeks 5–7", title: "Basic Python Primer", org: "Self-Paced Core", tier: "None", code: "Coding", focus: "Variables, loops, functions, running scripts.", link: "https://docs.python.org/3/tutorial/", xp: 150,
          flashcards: [
            { id: "s3_c1", q: "What is the lookup complexity of a Python Dictionary vs List?", a: "Dictionary key lookup is O(1) average time via hash tables; List search is O(n) linear search." }
          ]
        },
        { id: "s4", weeks: "Weeks 8–9", title: "Anthropic Academy (Claude API + MCP)", org: "Anthropic", tier: "S", code: "Light Code", focus: "Model Context Protocol & structured tool calling.", link: "https://anthropic.skilljar.com", xp: 200,
          flashcards: [
            { id: "s4_c1", q: "What is the architectural purpose of Model Context Protocol (MCP)?", a: "MCP standardizes how LLM applications connect to local and remote tools, files, and prompts without bespoke integration code." }
          ]
        }
      ]
    },
    {
      id: "p3", name: "3. Core ML", region: "Phase 3 • Classical Machine Learning",
      stages: [
        { id: "s5", weeks: "Weeks 10–17", title: "Machine Learning Specialization", org: "Andrew Ng / Stanford", tier: "S", code: "Coding", focus: "Cost functions, gradient descent, regularization.", link: "https://www.coursera.org/specializations/machine-learning-introduction", xp: 350,
          flashcards: [
            { id: "s5_c1", q: "Why does L1 Regularization (Lasso) generate sparse weight vectors?", a: "L1 adds an absolute value penalty whose diamond constraint boundary intersects axes at corners, driving non-critical weights to exact zero." }
          ]
        },
        { id: "s6", weeks: "Weeks 18–24", title: "CS50's Intro to AI with Python", org: "Harvard", tier: "A", code: "Coding", focus: "Search, minimax, constraint satisfaction, probability.", link: "https://cs50.harvard.edu/ai", xp: 300,
          flashcards: [
            { id: "s6_c1", q: "What condition makes A* heuristic search mathematically optimal?", a: "The heuristic must be admissible (never overestimates the true distance to the goal) and consistent/monotonic." }
          ]
        }
      ]
    },
    {
      id: "p4", name: "4. Deep Learning", region: "Phase 4 • Neural Architectures",
      stages: [
        { id: "s7", weeks: "Weeks 25–33", title: "Deep Learning Specialization", org: "Andrew Ng / DeepLearning.AI", tier: "S", code: "Coding", focus: "Multi-layer perceptrons, backprop, Adam, CNNs.", link: "https://www.coursera.org/specializations/deep-learning", xp: 400,
          flashcards: [
            { id: "s7_c1", q: "What catastrophic failure occurs if dense network weights are initialized to 0.0?", a: "Symmetry trap: every neuron computes identical activations and gradients, preventing the network from learning distinct features." }
          ]
        },
        { id: "s8", weeks: "Weeks 34–37", title: "MIT 6.S191: Intro to Deep Learning", org: "MIT", tier: "A", code: "Coding", focus: "Modern deep generative models and vision labs.", link: "http://introtodeeplearning.com", xp: 250,
          flashcards: [
            { id: "s8_c1", q: "How does Batch Normalization stabilize deep network training?", a: "It normalizes activations to zero mean and unit variance per mini-batch, reducing internal covariate shift and smoothing the loss landscape." }
          ]
        }
      ]
    },
    {
      id: "p5", name: "5. LLMs & Agents", region: "Phase 5 • Transformers & Orchestration",
      stages: [
        { id: "s9", weeks: "Weeks 38–46", title: "Hugging Face LLM Course", org: "Hugging Face", tier: "S", code: "Coding", focus: "Self-attention math, tokenization, fine-tuning.", link: "https://huggingface.co/learn/llm-course", xp: 450,
          flashcards: [
            { id: "s9_c1", q: "How does causal masking in decoder Transformers prevent data leakage?", a: "It replaces future attention logits with -infinity before softmax, ensuring token t can only attend to positions <= t." }
          ]
        },
        { id: "s10", weeks: "Weeks 47–52", title: "Generative AI with LLMs", org: "DeepLearning.AI + AWS", tier: "S", code: "Coding", focus: "LoRA/QLoRA parameter-efficient adaptation, RLHF.", link: "https://www.coursera.org/learn/generative-ai-with-llms", xp: 350,
          flashcards: [
            { id: "s10_c1", q: "How does LoRA reduce trainable memory footprint during fine-tuning?", a: "It freezes the base weight matrix W (d x k) and trains low-rank adapters A (d x r) and B (r x k) where r << min(d, k), reducing parameters by >99%." }
          ]
        },
        { id: "s11", weeks: "Weeks 53–60", title: "Hugging Face AI Agents Course", org: "Hugging Face", tier: "S", code: "Coding", focus: "Autonomous agent cycles, tool use, LangGraph.", link: "https://huggingface.co/learn/agents-course", xp: 450,
          flashcards: [
            { id: "s11_c1", q: "What is the core execution loop of a ReAct agent?", a: "An interleaved cycle of Thought (reasoning trace) -> Action (tool call) -> Observation (environment feedback)." }
          ]
        }
      ]
    },
    {
      id: "p6", name: "6. RAG & Evaluation", region: "Phase 6 • Retrieval & Hallucination Defense",
      stages: [
        { id: "s12", weeks: "Weeks 61–63", title: "Building Advanced RAG", org: "DeepLearning.AI", tier: "B", code: "Coding", focus: "Chunking, vector embeddings, cross-encoders.", link: "https://www.deeplearning.ai/short-courses/building-evaluating-advanced-rag/", xp: 200,
          flashcards: [
            { id: "s12_c1", q: "Why is Cosine Similarity preferred over Euclidean distance for text embeddings?", a: "Cosine similarity measures angular direction rather than vector length, preventing document length from biasing relevance." }
          ]
        },
        { id: "s13", weeks: "Weeks 63–65", title: "LangChain & Vector DBs", org: "DeepLearning.AI", tier: "B", code: "Coding", focus: "HNSW indexing, filtering, production latency.", link: "https://www.deeplearning.ai/short-courses/langchain-vector-databases-in-production/", xp: 200,
          flashcards: [
            { id: "s13_c1", q: "What trade-off does an HNSW index make?", a: "It trades exact nearest-neighbor precision and memory footprint for logarithmic O(log N) approximate nearest-neighbor query speed." }
          ]
        },
        { id: "s14", weeks: "Weeks 66–67", title: "RAGAS Framework Docs", org: "Official Docs", tier: "None", code: "Coding", focus: "Faithfulness, Answer Relevance, Context Precision.", link: "https://docs.ragas.io/", xp: 200,
          flashcards: [
            { id: "s14_c1", q: "What is the difference between Faithfulness and Answer Relevance in RAGAS?", a: "Faithfulness measures if the answer is strictly grounded in retrieved chunks; Answer Relevance measures if the answer addresses the prompt." }
          ]
        },
        { id: "s15", weeks: "Weeks 68–69", title: "OWASP LLM Top 10 Security", org: "OWASP", tier: "None", code: "Light Code", focus: "Prompt injections, jailbreak defense, guardrails.", link: "https://owasp.org/www-project-top-10-for-large-language-model-applications/", xp: 200,
          flashcards: [
            { id: "s15_c1", q: "What is an Indirect Prompt Injection?", a: "An attacker injects malicious instructions inside external data (web page, PDF, email) that an LLM ingests during runtime." }
          ]
        }
      ]
    },
    {
      id: "p7", name: "7. Deployment", region: "Phase 7 • Public Production URL",
      stages: [
        { id: "s16", weeks: "Weeks 70–72", title: "Production Deployment (FastAPI/Spaces)", org: "HF / Streamlit", tier: "None", code: "Coding", focus: "Containerization, inference API, live public URL.", link: "https://huggingface.co/spaces", xp: 300,
          flashcards: [
            { id: "s16_c1", q: "Why use Multi-Stage Docker builds for Python ML deployments?", a: "To separate heavy build tools and compilers from the final runtime image, drastically reducing attack surface and image size." }
          ]
        }
      ]
    }
  ],
  bossProjects: [
    { id: "bp1", num: 1, title: "AI Impact / Landscape Brief", diff: "Beginner", icon: "📝", why: "AI literacy writing sample", xp: 150, stack: "Markdown, Research", repoName: "ai-impact-brief" },
    { id: "bp2", num: 2, title: "Data-Cleaning + Analysis Script", diff: "Beginner", icon: "🧹", why: "Clean executed Python script", xp: 200, stack: "Python, Pandas, NumPy", repoName: "python-data-etl" },
    { id: "bp3", num: 3, title: "ML Model Comparison (Churn)", diff: "Intermediate", icon: "📊", why: "Model selection & metrics", xp: 250, stack: "Scikit-Learn, XGBoost", repoName: "churn-ml-benchmarks" },
    { id: "bp4", num: 4, title: "CNN Image Classifier + Writeup", diff: "Intermediate", icon: "👁️", why: "Diagnosing neural network failures", xp: 300, stack: "PyTorch, Torchvision", repoName: "cnn-classifier-pytorch" },
    { id: "bp5", num: 5, title: "Fine-Tuned Model on HF Hub", diff: "Intermediate", icon: "🤗", why: "Transfer learning public artifact", xp: 350, stack: "Transformers, Datasets", repoName: "hf-fine-tuned-model" },
    { id: "bp6", num: 6, title: "QLoRA Domain Fine-Tune", diff: "Advanced", icon: "⚡", why: "Efficient LLM adaptation", xp: 400, stack: "PEFT, BitsAndBytes, TRL", repoName: "qlora-domain-adaptation" },
    { id: "bp7", num: 7, title: "Multi-Agent Research System", diff: "Advanced", icon: "🤖", why: "Orchestration & tool use", xp: 500, stack: "LangGraph, CrewAI, Claude API", repoName: "multi-agent-system" },
    { id: "bp8", num: 8, title: "Enterprise RAG + RAGAS Evaluation", diff: "Advanced", icon: "🛡️", why: "Hallucination defense & retrieval scoring", xp: 500, stack: "ChromaDB, LlamaIndex, RAGAS", repoName: "enterprise-rag-ragas" },
    { id: "bp9", num: 9, title: "AI Security Lab (Red-Team Lab)", diff: "Advanced", icon: "🔒", why: "Prompt injection mitigation", xp: 450, stack: "NeMo Guardrails, OWASP", repoName: "ai-redteam-security" },
    { id: "bp10", num: 10, title: "Deployed Capstone (Full App / CLI)", diff: "Advanced", icon: "🚀", why: "Live end-to-end production artifact", xp: 600, stack: "FastAPI, Docker, Streamlit", repoName: "production-ai-capstone" }
  ],
  glossary: {
    "backpropagation": "Calculates error gradients backwards from the loss function through layers using the calculus chain rule to adjust weights.",
    "lora": "Low-Rank Adaptation freezes base weights and trains two smaller decomposed matrices (A & B) to adapt LLMs with 99% less VRAM.",
    "qlora": "Quantized LoRA quantizes base LLM weights to 4-bit NormalFloat precision before attaching 16-bit LoRA adapter matrices.",
    "rag": "Retrieval-Augmented Generation retrieves factual document chunks from a vector database and passes them to the LLM context prompt.",
    "hnsw": "Hierarchical Navigable Small World is a multi-layer graph index for fast logarithmic approximate nearest-neighbor vector search.",
    "mcp": "Model Context Protocol is an open standard that allows LLM applications to access external tools, APIs, and file contexts.",
    "chain of thought": "Prompting technique that forces LLMs to generate intermediate reasoning steps before arriving at a final solution.",
    "adam": "An adaptive learning rate optimization algorithm combining Momentum (moving average of gradients) and RMSProp (moving average of squared gradients)."
  }
};

function seedSM2DeckFromCurriculum() {
  const deck = [];
  CURRICULUM.phases.forEach((phase) => {
    phase.stages.forEach((stage) => {
      if (stage.flashcards && Array.isArray(stage.flashcards)) {
        stage.flashcards.forEach((card) => {
          deck.push(sm2CreateCard(card.id, card.q, card.a));
        });
      }
    });
  });
  return deck;
}
